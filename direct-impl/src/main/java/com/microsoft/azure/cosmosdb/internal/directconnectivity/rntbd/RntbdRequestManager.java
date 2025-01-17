/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd;

import com.google.common.base.Strings;
import com.microsoft.azure.cosmosdb.BridgeInternal;
import com.microsoft.azure.cosmosdb.DocumentClientException;
import com.microsoft.azure.cosmosdb.Error;
import com.microsoft.azure.cosmosdb.internal.InternalServerErrorException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ConflictException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ForbiddenException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.GoneException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.LockedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.MethodNotAllowedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.PartitionKeyRangeGoneException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.PreconditionFailedException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestEntityTooLargeException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestRateTooLargeException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RequestTimeoutException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.RetryWithException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.ServiceUnavailableException;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.StoreResponse;
import com.microsoft.azure.cosmosdb.internal.directconnectivity.UnauthorizedException;
import com.microsoft.azure.cosmosdb.rx.internal.BadRequestException;
import com.microsoft.azure.cosmosdb.rx.internal.InvalidPartitionException;
import com.microsoft.azure.cosmosdb.rx.internal.NotFoundException;
import com.microsoft.azure.cosmosdb.rx.internal.PartitionIsMigratingException;
import com.microsoft.azure.cosmosdb.rx.internal.PartitionKeyRangeIsSplittingException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.CoalescingBufferQueue;
import io.netty.channel.EventLoop;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import io.netty.util.Timeout;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.ThrowableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.microsoft.azure.cosmosdb.internal.HttpConstants.StatusCodes;
import static com.microsoft.azure.cosmosdb.internal.HttpConstants.SubStatusCodes;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdClientChannelHealthChecker.Timestamps;
import static com.microsoft.azure.cosmosdb.internal.directconnectivity.rntbd.RntbdConstants.RntbdResponseHeader;

public final class RntbdRequestManager implements ChannelHandler, ChannelInboundHandler, ChannelOutboundHandler {

    // region Fields

    private static final ClosedChannelException ON_CHANNEL_UNREGISTERED =
        ThrowableUtil.unknownStackTrace(new ClosedChannelException(), RntbdRequestManager.class, "channelUnregistered");

    private static final ClosedChannelException ON_CLOSE =
        ThrowableUtil.unknownStackTrace(new ClosedChannelException(), RntbdRequestManager.class, "close");

    private static final ClosedChannelException ON_DEREGISTER =
        ThrowableUtil.unknownStackTrace(new ClosedChannelException(), RntbdRequestManager.class, "deregister");

    private static final Logger logger = LoggerFactory.getLogger(RntbdRequestManager.class);

    private final CompletableFuture<RntbdContext> contextFuture = new CompletableFuture<>();
    private final CompletableFuture<RntbdContextRequest> contextRequestFuture = new CompletableFuture<>();
    private final ChannelHealthChecker healthChecker;
    private final int pendingRequestLimit;
    private final ConcurrentHashMap<Long, RntbdRequestRecord> pendingRequests;
    private final Timestamps timestamps = new Timestamps();

    private boolean closingExceptionally = false;
    private CoalescingBufferQueue pendingWrites;

    // endregion

    public RntbdRequestManager(final ChannelHealthChecker healthChecker, final int pendingRequestLimit) {

        checkArgument(pendingRequestLimit > 0, "pendingRequestLimit: %s", pendingRequestLimit);
        checkNotNull(healthChecker, "healthChecker");

        this.pendingRequests = new ConcurrentHashMap<>(pendingRequestLimit);
        this.pendingRequestLimit = pendingRequestLimit;
        this.healthChecker = healthChecker;
    }

    // region ChannelHandler methods

    /**
     * Gets called after the {@link ChannelHandler} was added to the actual context and it's ready to handle events.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void handlerAdded(final ChannelHandlerContext context) {
        this.traceOperation(context, "handlerAdded");
    }

    /**
     * Gets called after the {@link ChannelHandler} was removed from the actual context and it doesn't handle events
     * anymore.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void handlerRemoved(final ChannelHandlerContext context) {
        this.traceOperation(context, "handlerRemoved");
    }

    // endregion

    // region ChannelInboundHandler methods

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} is now active
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelActive(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelActive");
        context.fireChannelActive();
    }

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was registered and has reached the end of its lifetime
     * <p>
     * This method will only be called after the channel is closed.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelInactive");
        context.fireChannelInactive();
    }

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} has read a message from its peer
     * <p>
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     * @param message The message read
     */
    @Override
    public void channelRead(final ChannelHandlerContext context, final Object message) {

        this.traceOperation(context, "channelRead");

        try {
            if (message instanceof RntbdResponse) {

                try {
                    this.messageReceived(context, (RntbdResponse)message);
                } catch (Throwable throwable) {
                    reportIssue(context, "{} ", message, throwable);
                    this.exceptionCaught(context, throwable);
                }

            } else {

                final IllegalStateException error = new IllegalStateException(
                    Strings.lenientFormat("expected message of %s, not %s: %s",
                        RntbdResponse.class, message.getClass(), message
                    )
                );

                reportIssue(context, "", error);
                this.exceptionCaught(context, error);
            }
        } finally {
            if (message instanceof ReferenceCounted) {
                boolean released = ((ReferenceCounted)message).release();
                reportIssueUnless(released, context, "failed to release message: {}", message);
            }
        }
    }

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} has fully consumed the most-recent message read
     * <p>
     * If {@link ChannelOption#AUTO_READ} is off, no further attempt to read inbound data from the current
     * {@link Channel} will be made until {@link ChannelHandlerContext#read} is called. This leaves time
     * for outbound messages to be written.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelReadComplete(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelReadComplete");
        this.timestamps.channelReadCompleted();
        context.fireChannelReadComplete();
    }

    /**
     * Constructs a {@link CoalescingBufferQueue} for buffering encoded requests until we have an {@link RntbdRequest}
     * <p>
     * This method then calls {@link ChannelHandlerContext#fireChannelRegistered()} to forward to the next
     * {@link ChannelInboundHandler} in the {@link ChannelPipeline}.
     * <p>
     * Sub-classes may override this method to change behavior.
     *
     * @param context the {@link ChannelHandlerContext} for which the bind operation is made
     */
    @Override
    public void channelRegistered(final ChannelHandlerContext context) {

        this.traceOperation(context, "channelRegistered");

        reportIssueUnless(this.pendingWrites == null, context, "pendingWrites: {}", pendingWrites);
        this.pendingWrites = new CoalescingBufferQueue(context.channel());

        context.fireChannelRegistered();
    }

    /**
     * The {@link Channel} of the {@link ChannelHandlerContext} was unregistered from its {@link EventLoop}
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelUnregistered(final ChannelHandlerContext context) {

        this.traceOperation(context, "channelUnregistered");

        if (!this.closingExceptionally) {
            this.completeAllPendingRequestsExceptionally(context, ON_CHANNEL_UNREGISTERED);
        } else {
            logger.warn("{} channelUnregistered exceptionally", context);
        }

        context.fireChannelUnregistered();
    }

    /**
     * Gets called once the writable state of a {@link Channel} changed. You can check the state with
     * {@link Channel#isWritable()}.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     */
    @Override
    public void channelWritabilityChanged(final ChannelHandlerContext context) {
        this.traceOperation(context, "channelWritabilityChanged");
        context.fireChannelWritabilityChanged();
    }

    /**
     * Processes {@link ChannelHandlerContext#fireExceptionCaught(Throwable)} in the {@link ChannelPipeline}
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     * @param cause   Exception caught
     */
    @Override
    @SuppressWarnings("deprecation")
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {

        // TODO: DANOBLE: replace RntbdRequestManager.exceptionCaught with read/write listeners
        //  Notes:
        //    ChannelInboundHandler.exceptionCaught is deprecated and--today, prior to deprecation--only catches read--
        //    i.e., inbound--exceptions.
        //    Replacements:
        //    * read listener: unclear as there is no obvious replacement
        //    * write listener: implemented by RntbdTransportClient.DefaultEndpoint.doWrite
        //  Links:
        //  https://msdata.visualstudio.com/CosmosDB/_workitems/edit/373213

        this.traceOperation(context, "exceptionCaught", cause);

        if (!this.closingExceptionally) {
            this.completeAllPendingRequestsExceptionally(context, cause);
            logger.warn("{} closing due to:", context, cause);
            context.flush().close();
        }
    }

    /**
     * Processes inbound events triggered by channel handlers in the {@link RntbdClientChannelHandler} pipeline
     * <p>
     * All but inbound request management events are ignored.
     *
     * @param context {@link ChannelHandlerContext} to which this {@link RntbdRequestManager} belongs
     * @param event   An object representing a user event
     */
    @Override
    public void userEventTriggered(final ChannelHandlerContext context, final Object event) {

        this.traceOperation(context, "userEventTriggered", event);

        try {

            if (event instanceof IdleStateEvent) {

                this.healthChecker.isHealthy(context.channel()).addListener((Future<Boolean> future) -> {

                    final Throwable cause;

                    if (future.isSuccess()) {
                        if (future.get()) {
                            return;
                        }
                        cause = UnhealthyChannelException.INSTANCE;
                    } else {
                        cause = future.cause();
                    }

                    this.exceptionCaught(context, cause);
                });

                return;
            }
            if (event instanceof RntbdContext) {
                this.contextFuture.complete((RntbdContext)event);
                this.removeContextNegotiatorAndFlushPendingWrites(context);
                return;
            }
            if (event instanceof RntbdContextException) {
                this.contextFuture.completeExceptionally((RntbdContextException)event);
                context.pipeline().flush().close();
                return;
            }
            context.fireUserEventTriggered(event);

        } catch (Throwable error) {
            reportIssue(context, "{}: ", event, error);
            this.exceptionCaught(context, error);
        }
    }

    // endregion

    // region ChannelOutboundHandler methods

    /**
     * Called once a bind operation is made.
     *
     * @param context      the {@link ChannelHandlerContext} for which the bind operation is made
     * @param localAddress the {@link SocketAddress} to which it should bound
     * @param promise      the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void bind(final ChannelHandlerContext context, final SocketAddress localAddress, final ChannelPromise promise) {
        this.traceOperation(context, "bind", localAddress);
        context.bind(localAddress, promise);
    }

    /**
     * Called once a close operation is made.
     *
     * @param context the {@link ChannelHandlerContext} for which the close operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void close(final ChannelHandlerContext context, final ChannelPromise promise) {

        this.traceOperation(context, "close");

        if (!this.closingExceptionally) {
            this.completeAllPendingRequestsExceptionally(context, ON_CLOSE);
        } else {
            logger.warn("{} closed exceptionally", context);
        }

        final SslHandler sslHandler = context.pipeline().get(SslHandler.class);

        if (sslHandler != null) {
            // Netty 4.1.36.Final: SslHandler.closeOutbound must be called before closing the pipeline
            // This ensures that all SSL engine and ByteBuf resources are released
            // This is something that does not occur in the call to ChannelPipeline.close that follows
            sslHandler.closeOutbound();
        }

        context.close(promise);
    }

    /**
     * Called once a connect operation is made.
     *
     * @param context       the {@link ChannelHandlerContext} for which the connect operation is made
     * @param remoteAddress the {@link SocketAddress} to which it should connect
     * @param localAddress  the {@link SocketAddress} which is used as source on connect
     * @param promise       the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void connect(
        final ChannelHandlerContext context, final SocketAddress remoteAddress, final SocketAddress localAddress,
        final ChannelPromise promise
    ) {
        this.traceOperation(context, "connect", remoteAddress, localAddress);
        context.connect(remoteAddress, localAddress, promise);
    }

    /**
     * Called once a deregister operation is made from the current registered {@link EventLoop}.
     *
     * @param context the {@link ChannelHandlerContext} for which the deregister operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void deregister(final ChannelHandlerContext context, final ChannelPromise promise) {

        this.traceOperation(context, "deregister");

        if (!this.closingExceptionally) {
            this.completeAllPendingRequestsExceptionally(context, ON_DEREGISTER);
        } else {
            logger.warn("{} deregistered exceptionally", context);
        }

        context.deregister(promise);
    }

    /**
     * Called once a disconnect operation is made.
     *
     * @param context the {@link ChannelHandlerContext} for which the disconnect operation is made
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void disconnect(final ChannelHandlerContext context, final ChannelPromise promise) {
        this.traceOperation(context, "disconnect");
        context.disconnect(promise);
    }

    /**
     * Called once a flush operation is made
     * <p>
     * The flush operation will try to flush out all previous written messages that are pending.
     *
     * @param context the {@link ChannelHandlerContext} for which the flush operation is made
     */
    @Override
    public void flush(final ChannelHandlerContext context) {
        this.traceOperation(context, "flush");
        context.flush();
    }

    /**
     * Intercepts {@link ChannelHandlerContext#read}
     *
     * @param context the {@link ChannelHandlerContext} for which the read operation is made
     */
    @Override
    public void read(final ChannelHandlerContext context) {
        this.traceOperation(context, "read");
        context.read();
    }

    /**
     * Called once a write operation is made
     * <p>
     * The write operation will send messages through the {@link ChannelPipeline} which are then ready to be flushed
     * to the actual {@link Channel}. This will occur when {@link Channel#flush} is called.
     *
     * @param context the {@link ChannelHandlerContext} for which the write operation is made
     * @param message the message to write
     * @param promise the {@link ChannelPromise} to notify once the operation completes
     */
    @Override
    public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {

        // TODO: DANOBLE: Ensure that all write errors are reported with a root cause of type EncoderException
        //  Requires a full scan of the rntbd code

        this.traceOperation(context, "write", message);

        if (message instanceof RntbdRequestRecord) {

            this.timestamps.channelWriteAttempted();

            context.write(this.addPendingRequestRecord(context, (RntbdRequestRecord)message), promise).addListener(completed -> {
                if (completed.isSuccess()) {
                    this.timestamps.channelWriteCompleted();
                }
            });

            return;
        }

        if (message == RntbdHealthCheckRequest.MESSAGE) {

            context.write(RntbdHealthCheckRequest.MESSAGE, promise).addListener(completed -> {
                if (completed.isSuccess()) {
                    this.timestamps.channelPingCompleted();
                }
            });

            return;
        }

        final IllegalStateException error = new IllegalStateException(Strings.lenientFormat("message of %s: %s", message.getClass(), message));
        reportIssue(context, "", error);
        this.exceptionCaught(context, error);
    }

    // endregion

    // region Package private methods

    int pendingRequestCount() {
        return this.pendingRequests.size();
    }

    Optional<RntbdContext> rntbdContext() {
        return Optional.of(this.contextFuture.getNow(null));
    }

    CompletableFuture<RntbdContextRequest> rntbdContextRequestFuture() {
        return this.contextRequestFuture;
    }

    boolean hasRequestedRntbdContext() {
        return this.contextRequestFuture.getNow(null) != null;
    }

    boolean hasRntbdContext() {
        return this.contextFuture.getNow(null) != null;
    }

    boolean isServiceable(final int demand) {
        final int limit = this.hasRntbdContext() ? this.pendingRequestLimit : Math.min(this.pendingRequestLimit, demand);
        return this.pendingRequests.size() < limit;
    }

    void pendWrite(final ByteBuf out, final ChannelPromise promise) {
        this.pendingWrites.add(out, promise);
    }

    RntbdClientChannelHealthChecker.Timestamps snapshotTimestamps() {
        return new RntbdClientChannelHealthChecker.Timestamps(this.timestamps);
    }

    // endregion

    // region Private methods

    private RntbdRequestArgs addPendingRequestRecord(final ChannelHandlerContext context, final RntbdRequestRecord record) {

        return this.pendingRequests.compute(record.transportRequestId(), (id, current) -> {

            boolean predicate = current == null;
            String format = "id: {}, current: {}, request: {}";

            reportIssueUnless(predicate, context, format, record);

            final Timeout pendingRequestTimeout = record.newTimeout(timeout -> {

                // We don't wish to complete on the timeout thread, but rather on a thread doled out by our executor

                EventExecutor executor = context.executor();

                if (executor.inEventLoop()) {
                    record.expire();
                } else {
                    executor.next().execute(record::expire);
                }
            });

            record.whenComplete((response, error) -> {
                this.pendingRequests.remove(id);
                pendingRequestTimeout.cancel();
            });

            return record;

        }).args();
    }

    private void completeAllPendingRequestsExceptionally(final ChannelHandlerContext context, final Throwable throwable) {

        reportIssueUnless(!this.closingExceptionally, context, "", throwable);
        this.closingExceptionally = true;

        if (this.pendingWrites != null && !this.pendingWrites.isEmpty()) {
            // an expensive call that fires at least one exceptionCaught event
            this.pendingWrites.releaseAndFailAll(context, throwable);
        }

        if (!this.pendingRequests.isEmpty()) {

            if (!this.contextRequestFuture.isDone()) {
                this.contextRequestFuture.completeExceptionally(throwable);
            }

            if (!this.contextFuture.isDone()) {
                this.contextFuture.completeExceptionally(throwable);
            }

            final int count = this.pendingRequests.size();
            Exception contextRequestException = null;
            String phrase = null;

            if (this.contextRequestFuture.isCompletedExceptionally()) {

                try {
                    this.contextRequestFuture.get();
                } catch (final CancellationException error) {
                    phrase = "RNTBD context request write cancelled";
                    contextRequestException = error;
                } catch (final Exception error) {
                    phrase = "RNTBD context request write failed";
                    contextRequestException = error;
                } catch (final Throwable error) {
                    phrase = "RNTBD context request write failed";
                    contextRequestException = new ChannelException(error);
                }

            } else if (this.contextFuture.isCompletedExceptionally()) {

                try {
                    this.contextFuture.get();
                } catch (final CancellationException error) {
                    phrase = "RNTBD context request read cancelled";
                    contextRequestException = error;
                } catch (final Exception error) {
                    phrase = "RNTBD context request read failed";
                    contextRequestException = error;
                } catch (final Throwable error) {
                    phrase = "RNTBD context request read failed";
                    contextRequestException = new ChannelException(error);
                }

            } else {

                phrase = "closed exceptionally";
            }

            final String message = Strings.lenientFormat("%s %s with %s pending requests", context, phrase, count);
            final Exception cause;

            if (throwable instanceof ClosedChannelException) {

                cause = contextRequestException == null
                    ? (ClosedChannelException)throwable
                    : contextRequestException;

            } else {

                cause = throwable instanceof Exception
                    ? (Exception)throwable
                    : new ChannelException(throwable);
            }

            for (RntbdRequestRecord record : this.pendingRequests.values()) {

                final Map<String, String> requestHeaders = record.args().serviceRequest().getHeaders();
                final String requestUri = record.args().physicalAddress().toString();

                final GoneException error = new GoneException(message, cause, (Map<String, String>)null, requestUri);
                BridgeInternal.setRequestHeaders(error, requestHeaders);

                record.completeExceptionally(error);
            }
        }
    }

    /**
     * This method is called for each incoming message of type {@link StoreResponse} to complete a request
     *
     * @param context  {@link ChannelHandlerContext} encode to which this {@link RntbdRequestManager} belongs
     * @param response the message encode handle
     */
    private void messageReceived(final ChannelHandlerContext context, final RntbdResponse response) {

        final Long transportRequestId = response.getTransportRequestId();

        if (transportRequestId == null) {
            reportIssue(context, "response ignored because its transport request identifier is missing: {}", response);
            return;
        }

        final RntbdRequestRecord pendingRequest = this.pendingRequests.get(transportRequestId);

        if (pendingRequest == null) {
            logger.warn("{} response ignored because there is no matching pending request: {}", context, response);
            return;
        }

        final HttpResponseStatus status = response.getStatus();
        final UUID activityId = response.getActivityId();

        if (HttpResponseStatus.OK.code() <= status.code() && status.code() < HttpResponseStatus.MULTIPLE_CHOICES.code()) {

            final StoreResponse storeResponse = response.toStoreResponse(this.contextFuture.getNow(null));
            pendingRequest.complete(storeResponse);

        } else {

            // Map response to a DocumentClientException

            final DocumentClientException cause;

            // ..Fetch required header values

            final long lsn = response.getHeader(RntbdResponseHeader.LSN);
            final String partitionKeyRangeId = response.getHeader(RntbdResponseHeader.PartitionKeyRangeId);

            // ..Create Error instance

            final Error error = response.hasPayload() ?
                BridgeInternal.createError(RntbdObjectMapper.readTree(response)) :
                new Error(Integer.toString(status.code()), status.reasonPhrase(), status.codeClass().name());

            // ..Map RNTBD response headers to HTTP response headers

            final Map<String, String> responseHeaders = response.getHeaders().asMap(
                this.rntbdContext().orElseThrow(IllegalStateException::new), activityId
            );

            // ..Create DocumentClientException based on status and sub-status codes

            switch (status.code()) {

                case StatusCodes.BADREQUEST:
                    cause = new BadRequestException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.CONFLICT:
                    cause = new ConflictException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.FORBIDDEN:
                    cause = new ForbiddenException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.GONE:

                    final int subStatusCode = Math.toIntExact(response.getHeader(RntbdResponseHeader.SubStatus));

                    switch (subStatusCode) {
                        case SubStatusCodes.COMPLETING_SPLIT:
                            cause = new PartitionKeyRangeIsSplittingException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.COMPLETING_PARTITION_MIGRATION:
                            cause = new PartitionIsMigratingException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.NAME_CACHE_IS_STALE:
                            cause = new InvalidPartitionException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        case SubStatusCodes.PARTITION_KEY_RANGE_GONE:
                            cause = new PartitionKeyRangeGoneException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                        default:
                            cause = new GoneException(error, lsn, partitionKeyRangeId, responseHeaders);
                            break;
                    }
                    break;

                case StatusCodes.INTERNAL_SERVER_ERROR:
                    cause = new InternalServerErrorException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.LOCKED:
                    cause = new LockedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.METHOD_NOT_ALLOWED:
                    cause = new MethodNotAllowedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.NOTFOUND:
                    cause = new NotFoundException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.PRECONDITION_FAILED:
                    cause = new PreconditionFailedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.REQUEST_ENTITY_TOO_LARGE:
                    cause = new RequestEntityTooLargeException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.REQUEST_TIMEOUT:
                    cause = new RequestTimeoutException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.RETRY_WITH:
                    cause = new RetryWithException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.SERVICE_UNAVAILABLE:
                    cause = new ServiceUnavailableException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.TOO_MANY_REQUESTS:
                    cause = new RequestRateTooLargeException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                case StatusCodes.UNAUTHORIZED:
                    cause = new UnauthorizedException(error, lsn, partitionKeyRangeId, responseHeaders);
                    break;

                default:
                    cause = new DocumentClientException(status.code(), error, responseHeaders);
                    break;
            }

            pendingRequest.completeExceptionally(cause);
        }
    }

    private void removeContextNegotiatorAndFlushPendingWrites(final ChannelHandlerContext context) {

        final RntbdContextNegotiator negotiator = context.pipeline().get(RntbdContextNegotiator.class);
        negotiator.removeInboundHandler();
        negotiator.removeOutboundHandler();

        if (!this.pendingWrites.isEmpty()) {
            this.pendingWrites.writeAndRemoveAll(context);
            context.flush();
        }
    }

    private static void reportIssue(final Object subject, final String format, final Object... args) {
        RntbdReporter.reportIssue(logger, subject, format, args);
    }

    private static void reportIssueUnless(
        final boolean predicate, final Object subject, final String format, final Object... args
    ) {
        RntbdReporter.reportIssueUnless(logger, predicate, subject, format, args);
    }

    private void traceOperation(final ChannelHandlerContext context, final String operationName, final Object... args) {
        logger.trace("{}\n{}\n{}", operationName, context, args);
    }

    // endregion

    // region Types

    private static final class UnhealthyChannelException extends ChannelException {

        static final UnhealthyChannelException INSTANCE = new UnhealthyChannelException();

        private UnhealthyChannelException() {
            super("health check failed");
        }

        @Override
        public Throwable fillInStackTrace() {
            return this;
        }
    }

    // endregion
}
