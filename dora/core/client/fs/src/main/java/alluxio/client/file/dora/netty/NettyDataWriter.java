/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.client.file.dora.netty;

import alluxio.client.block.stream.DataWriter;
import alluxio.client.file.FileSystemContext;
import alluxio.client.file.options.OutStreamOptions;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.status.AlluxioStatusException;
import alluxio.exception.status.CanceledException;
import alluxio.exception.status.DeadlineExceededException;
import alluxio.exception.status.UnavailableException;
import alluxio.grpc.RequestType;
import alluxio.network.protocol.RPCProtoMessage;
import alluxio.network.protocol.databuffer.DataBuffer;
import alluxio.network.protocol.databuffer.NettyDataBuffer;
import alluxio.proto.dataserver.Protocol;
import alluxio.proto.status.Status.PStatus;
import alluxio.resource.LockResource;
import alluxio.util.CommonUtils;
import alluxio.util.proto.ProtoMessage;
import alluxio.wire.WorkerNetAddress;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A netty packet writer that streams a full block or a UFS file to a netty data server.
 * <p>
 * Protocol:
 * 1. The client streams packets (start from pos 0) to the server. The client pauses if the client
 * buffer is full, resumes if the buffer is not full.
 * 2. The server reads packets from the channel and writes them to the block worker. See the server
 * side implementation for details.
 * 3. The client can either send an EOF packet or a CANCEL packet to end the write request. The
 * client has to wait for the response from the data server for the EOF or CANCEL packet to make
 * sure that the server has cleaned its states.
 * 4. To make it simple to handle errors, the channel is closed if any error occurs.
 * <p>
 * NOTE: this class is NOT threadsafe. Do not call cancel/close while some other threads are
 * writing.
 */
@NotThreadSafe
public class NettyDataWriter implements DataWriter {
  private static final Logger LOG = LoggerFactory.getLogger(NettyDataWriter.class);

  private static final int MAX_PACKETS_IN_FLIGHT =
      Configuration.getInt(PropertyKey.USER_NETWORK_NETTY_WRITER_BUFFER_SIZE_PACKETS);
  private static final long WRITE_TIMEOUT_MS =
      Configuration.getMs(PropertyKey.USER_NETWORK_NETTY_TIMEOUT_MS);
  private static final long CLOSE_TIMEOUT_MS =
      Configuration.getMs(PropertyKey.USER_NETWORK_NETTY_WRITER_CLOSE_TIMEOUT_MS);

  private final FileSystemContext mContext;
  private final Channel mChannel;
  private final WorkerNetAddress mAddress;
  private final long mLength;
  protected Protocol.WriteRequest mPartialRequest;
  private final long mPacketSize;

  private boolean mClosed;

  private final ReentrantLock mLock = new ReentrantLock();
  /**
   * The next pos to write to the channel.
   */
  @GuardedBy("mLock")
  private long mPosToWrite;
  /**
   * The next pos to queue to the netty buffer. mPosToQueue - mPosToWrite is the data sitting
   * in the netty buffer.
   */
  @GuardedBy("mLock")
  private long mPosToQueue;
  @GuardedBy("mLock")
  private Throwable mPacketWriteException;
  @GuardedBy("mLock")
  private boolean mDone;
  @GuardedBy("mLock")
  private boolean mEOFSent;
  @GuardedBy("mLock")
  private boolean mCancelSent;
  /**
   * This condition is met if mPacketWriteException != null or mDone = true.
   */
  private final Condition mDoneOrFailed = mLock.newCondition();
  /**
   * This condition is met if mPacketWriteException != null or the buffer is not full.
   */
  private final Condition mBufferNotFullOrFailed = mLock.newCondition();
  /**
   * This condition is met if there is nothing in the netty buffer.
   */
  private final Condition mBufferEmptyOrFailed = mLock.newCondition();

  /**
   * @param context the file system context
   * @param address the data server address
   * @param length  the length of the block or file to write, set to Long.MAX_VALUE if unknown
   * @param type    type of the write request
   * @param options the options of the output stream
   * @return an instance of {@link NettyDataWriter}
   */
  public static NettyDataWriter create(FileSystemContext context, WorkerNetAddress address,
                                       long length, RequestType type,
                                       OutStreamOptions options) throws IOException {
    long packetSize =
        Configuration.getBytes(PropertyKey.USER_NETWORK_NETTY_WRITER_PACKET_SIZE_BYTES);
    Channel nettyChannel = context.acquireNettyChannel(address);
    return new NettyDataWriter(context, address, length, packetSize, type, options,
        nettyChannel);
  }

  protected Protocol.RequestType getRequestType(RequestType requestType) {
    switch (requestType) {
      case ALLUXIO_BLOCK:
        return Protocol.RequestType.ALLUXIO_BLOCK;
      case UFS_FILE:
        return Protocol.RequestType.UFS_FILE;
      case UFS_FALLBACK_BLOCK:
        throw new UnsupportedOperationException("Unsupported request type UFS_FALLBACK_BLOCK. ");
      default:
        throw new UnsupportedOperationException("Request type needs to be specified. ");
    }
  }

  /**
   * Creates an instance of {@link NettyDataWriter}.
   *
   * @param context    the file system context
   * @param address    the data server address
   * @param length     the length of the file to write, set to Long.MAX_VALUE if unknown
   * @param packetSize the packet size
   * @param type       type of the write request
   * @param options    details of the write request which are constant for all requests
   * @param channel    netty channel
   */
  protected NettyDataWriter(FileSystemContext context, final WorkerNetAddress address,
                          long length, long packetSize, RequestType type, OutStreamOptions options,
                          Channel channel) {
    mContext = context;
    mAddress = address;
    mLength = length;
    Protocol.WriteRequest.Builder builder =
        Protocol.WriteRequest.newBuilder().setTier(options.getWriteTier())
            .setType(getRequestType(type));

    Protocol.CreateUfsFileOptions ufsFileOptions =
        Protocol.CreateUfsFileOptions.newBuilder().setUfsPath(options.getUfsPath())
            .setOwner(options.getOwner()).setGroup(options.getGroup())
            .setMode(options.getMode().toShort()).setMountId(options.getMountId()).build();
    builder.setCreateUfsFileOptions(ufsFileOptions);

    mPartialRequest = builder.buildPartial();
    mPacketSize = packetSize;
    mChannel = channel;
    mChannel.pipeline().addLast(new PacketWriteResponseHandler());
  }

  @Override
  public long pos() {
    try (LockResource lr = new LockResource(mLock)) {
      return mPosToQueue;
    }
  }

  @Override
  public Optional<String> getUfsContentHash() {
    return Optional.empty();
  }

  /**
   * Write chunk by sending a write request.
   * @param bytes the bytes to be written
   * @param off the offset of the given byte array
   * @param len the length to be written
   * @throws IOException
   */
  public void writeChunk(byte[] bytes, int off, int len) throws IOException {
    long totalBytesLeft = len;
    int bytesToWrite = (int) Math.min(len, mPacketSize);
    int tmpOffset = off;
    while (totalBytesLeft > 0) {
      // TODO(JiamingMai): maybe we can reduce copying here
      ByteBuf byteBuf = mChannel.alloc().buffer(bytesToWrite);
      byteBuf.writeBytes(bytes, tmpOffset, bytesToWrite);
      writeChunk(byteBuf);
      tmpOffset += bytesToWrite;
      totalBytesLeft -= bytesToWrite;
      bytesToWrite = (int) Math.min(totalBytesLeft, mPacketSize);
    }
  }

  @Override
  public void writeChunk(final ByteBuf buf) throws IOException {
    final long len;
    final long offset;
    try (LockResource lr = new LockResource(mLock)) {
      Preconditions.checkState(!mClosed && !mEOFSent && !mCancelSent);
      Preconditions.checkArgument(buf.readableBytes() <= mPacketSize);
      while (true) {
        if (mPacketWriteException != null) {
          Throwables.propagateIfPossible(mPacketWriteException, IOException.class);
          throw AlluxioStatusException.fromCheckedException(mPacketWriteException);
        }
        if (!tooManyPacketsInFlight()) {
          offset = mPosToQueue;
          mPosToQueue += buf.readableBytes();
          len = buf.readableBytes();
          break;
        }
        try {
          if (!mBufferNotFullOrFailed.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new DeadlineExceededException(
                String.format("Timeout writing to %s for request %s after %dms.",
                    mAddress, mPartialRequest, WRITE_TIMEOUT_MS));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CanceledException(e);
        }
      }
    } catch (Throwable e) {
      buf.release();
      throw e;
    }

    Protocol.WriteRequest writeRequest = mPartialRequest.toBuilder()
        .setOffset(offset)
        .build();
    DataBuffer dataBuffer = new NettyDataBuffer(buf);
    try {
      mChannel.writeAndFlush(new RPCProtoMessage(new ProtoMessage(writeRequest), dataBuffer))
          .addListener(new WriteListener(offset + len)).sync();
    } catch (InterruptedException e) {
      // ignore
    }
  }

  @Override
  public void cancel() {
    if (mClosed) {
      return;
    }
    sendCancel();
  }

  @Override
  public void flush() throws IOException {
    mChannel.flush();

    try (LockResource lr = new LockResource(mLock)) {
      while (true) {
        if (mPosToWrite >= mPosToQueue) {
          return;
        }
        if (mPacketWriteException != null) {
          Throwables.propagateIfPossible(mPacketWriteException, IOException.class);
          throw AlluxioStatusException.fromCheckedException(mPacketWriteException);
        }
        if (!mBufferEmptyOrFailed.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          throw new DeadlineExceededException(
              String.format("Timeout flushing to %s for request %s after %dms.",
                  mAddress, mPartialRequest, WRITE_TIMEOUT_MS));
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CanceledException(e);
    }
  }

  @Override
  public void close() throws IOException {
    if (mClosed) {
      return;
    }

    sendEof();
    Future<?> closeFuture = null;
    mLock.lock();
    try {
      while (true) {
        if (mDone) {
          return;
        }
        try {
          if (mPacketWriteException != null) {
            closeFuture = mChannel.eventLoop().submit(new Runnable() {
              @Override
              public void run() {
                mChannel.close();
              }
            });
            throw new UnavailableException(mPacketWriteException);
          }
          if (!mDoneOrFailed.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            closeFuture = mChannel.eventLoop().submit(new Runnable() {
              @Override
              public void run() {
                mChannel.close();
              }
            });
            throw new DeadlineExceededException(String.format(
                "Timeout closing PacketWriter to %s for request %s after %dms.",
                mAddress, mPartialRequest, CLOSE_TIMEOUT_MS));
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CanceledException(e);
        }
      }
    } finally {
      mLock.unlock();
      if (closeFuture != null) {
        try {
          closeFuture.sync();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new CanceledException(e);
        }
      }
      if (mChannel.isOpen()) {
        mChannel.pipeline().removeLast();
      }
      mContext.releaseNettyChannel(mAddress, mChannel);
      mClosed = true;
    }
  }

  /**
   * @return true if there are too many bytes in flight
   */
  private boolean tooManyPacketsInFlight() {
    return mPosToQueue - mPosToWrite >= MAX_PACKETS_IN_FLIGHT * mPacketSize;
  }

  /**
   * Sends an EOF packet to end the write request of the stream.
   */
  private void sendEof() {
    final long pos;
    try (LockResource lr = new LockResource(mLock)) {
      if (mEOFSent || mCancelSent) {
        return;
      }
      mEOFSent = true;
      pos = mPosToQueue;
    }
    // Write the EOF packet.
    Protocol.WriteRequest writeRequest =
        mPartialRequest.toBuilder().setOffset(pos).setEof(true).build();
    mChannel.writeAndFlush(new RPCProtoMessage(new ProtoMessage(writeRequest), null))
        .addListener(new EofOrCancelListener());
  }

  /**
   * Sends a CANCEL packet to end the write request of the stream.
   */
  private void sendCancel() {
    final long pos;
    try (LockResource lr = new LockResource(mLock)) {
      if (mEOFSent || mCancelSent) {
        return;
      }
      mCancelSent = true;
      pos = mPosToQueue;
    }
    // Write the CANCEL packet.
    Protocol.WriteRequest writeRequest =
        mPartialRequest.toBuilder().setOffset(pos).setCancel(true).build();
    mChannel.writeAndFlush(new RPCProtoMessage(new ProtoMessage(writeRequest), null))
        .addListener(new EofOrCancelListener());
  }

  @Override
  public int chunkSize() {
    return (int) mPacketSize;
  }

  /**
   * Updates the channel exception to be the given exception e, or adds e to suppressed exceptions.
   *
   * @param e Exception received
   */
  @GuardedBy("mLock")
  private void updateException(Throwable e) {
    if (mPacketWriteException == null || mPacketWriteException == e) {
      mPacketWriteException = e;
    } else {
      mPacketWriteException.addSuppressed(e);
    }
  }

  /**
   * The netty handler that handles netty write response.
   */
  private final class PacketWriteResponseHandler extends ChannelInboundHandlerAdapter {
    /**
     * Default constructor.
     */
    PacketWriteResponseHandler() {
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws IOException {
      Preconditions.checkState(acceptMessage(msg), "Incorrect response type %s.", msg);
      RPCProtoMessage response = (RPCProtoMessage) msg;
      // Canceled is considered a valid status and handled in the writer. We avoid creating a
      // CanceledException as an optimization.
      if (response.getMessage().asResponse().getStatus() != PStatus.CANCELLED) {
        CommonUtils.unwrapResponseFrom(response.getMessage().asResponse(), ctx.channel());
      }

      try (LockResource lr = new LockResource(mLock)) {
        mDone = true;
        mDoneOrFailed.signal();
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      LOG.error("Exception is caught when writing block {} to channel {}:",
          mPartialRequest.getId(), ctx.channel(), cause);
      try (LockResource lr = new LockResource(mLock)) {
        updateException(cause);
        mBufferNotFullOrFailed.signal();
        mDoneOrFailed.signal();
        mBufferEmptyOrFailed.signal();
      }
      ctx.close();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) {
      LOG.warn("Channel {} is closed.", ctx.channel());
      try (LockResource lr = new LockResource(mLock)) {
        if (!mDone) {
          updateException(new IOException(String
              .format("Channel %s is closed when writing block %d.", ctx.channel(),
                  mPartialRequest.getId())));
          mBufferNotFullOrFailed.signal();
          mDoneOrFailed.signal();
          mBufferEmptyOrFailed.signal();
        }
      }
      ctx.fireChannelUnregistered();
    }

    /**
     * @param msg the message received
     * @return true if this message should be processed
     */
    private boolean acceptMessage(Object msg) {
      if (msg instanceof RPCProtoMessage) {
        return ((RPCProtoMessage) msg).getMessage().isResponse();
      }
      return false;
    }
  }

  /**
   * The netty channel future listener that is called when a packet write is complete.
   */
  private final class WriteListener implements ChannelFutureListener {
    private final long mPosToWriteUncommitted;

    /**
     * @param posToWriteUncommitted the pos to commit (i.e. update mPosToWrite)
     */
    WriteListener(long posToWriteUncommitted) {
      mPosToWriteUncommitted = posToWriteUncommitted;
    }

    @Override
    public void operationComplete(ChannelFuture future) {
      if (!future.isSuccess()) {
        future.channel().close();
      }
      boolean shouldSendEOF = false;
      try (LockResource lr = new LockResource(mLock)) {
        Preconditions.checkState(mPosToWriteUncommitted - mPosToWrite <= mPacketSize,
            "Some packet is not acked.");
        Preconditions.checkState(mPosToWriteUncommitted <= mLength);
        mPosToWrite = mPosToWriteUncommitted;

        if (future.cause() != null) {
          updateException(future.cause());
          mDoneOrFailed.signal();
          mBufferNotFullOrFailed.signal();
          mBufferEmptyOrFailed.signal();
          return;
        }
        if (mPosToWrite == mPosToQueue) {
          mBufferEmptyOrFailed.signal();
        }
        if (!tooManyPacketsInFlight()) {
          mBufferNotFullOrFailed.signal();
        }
        if (mPosToWrite == mLength) {
          shouldSendEOF = true;
        }
      }
      if (shouldSendEOF) {
        sendEof();
      }
    }
  }

  /**
   * The netty channel future listener that is called when a EOF or CANCEL is complete.
   */
  private final class EofOrCancelListener implements ChannelFutureListener {
    /**
     * Constructor.
     */
    EofOrCancelListener() {
    }

    @Override
    public void operationComplete(ChannelFuture future) {
      if (!future.isSuccess()) {
        future.channel().close();
        try (LockResource lr = new LockResource(mLock)) {
          updateException(future.cause());
          mDoneOrFailed.signal();
          mBufferNotFullOrFailed.signal();
          mBufferEmptyOrFailed.signal();
        }
      }
    }
  }
}

