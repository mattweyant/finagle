package com.twitter.finagle.http

/**
 * This puts it all together: The HTTP codec itself.
 */

import com.twitter.conversions.storage._
import com.twitter.finagle._
import com.twitter.finagle.transport.Transport
import com.twitter.finagle.http.codec._
import com.twitter.finagle.http.filter.DtabFilter
import com.twitter.finagle.stats.{StatsReceiver, NullStatsReceiver}
import com.twitter.finagle.tracing._
import com.twitter.util.{Try, StorageUnit, Future, Closable}
import java.net.InetSocketAddress
import org.jboss.netty.channel.{
  ChannelPipelineFactory, UpstreamMessageEvent, Channel, Channels,
  ChannelEvent, ChannelHandlerContext, SimpleChannelDownstreamHandler, MessageEvent}
import org.jboss.netty.handler.codec.http._

case class BadHttpRequest(httpVersion: HttpVersion, method: HttpMethod, uri: String, exception: Exception)
  extends DefaultHttpRequest(httpVersion, method, uri)

object BadHttpRequest {
  def apply(exception: Exception) =
    new BadHttpRequest(HttpVersion.HTTP_1_0, HttpMethod.GET, "/bad-http-request", exception)
}

/** Convert exceptions to BadHttpRequests */
class SafeHttpServerCodec(
    maxInitialLineLength: Int,
    maxHeaderSize: Int,
    maxChunkSize: Int)
  extends HttpServerCodec(maxInitialLineLength, maxHeaderSize, maxChunkSize)
{
  override def handleUpstream(ctx: ChannelHandlerContext, e: ChannelEvent) {
    // this only catches Codec exceptions -- when a handler calls sendUpStream(), it
    // rescues exceptions from the upstream handlers and calls notifyHandlerException(),
    // which doesn't throw exceptions.
    try {
     super.handleUpstream(ctx, e)
    } catch {
      case ex: Exception =>
        val channel = ctx.getChannel()
        ctx.sendUpstream(new UpstreamMessageEvent(
          channel, BadHttpRequest(ex), channel.getRemoteAddress()))
    }
  }
}

/**
 * @param _compressionLevel The compression level to use. If passed the default value (-1) then use
 * [[com.twitter.finagle.http.codec.TextualContentCompressor TextualContentCompressor]] which will
 * compress text-like content-types with the default compression level (6). Otherwise, use
 * [[org.jboss.netty.handler.codec.http.HttpContentCompressor HttpContentCompressor]] for all
 * content-types with specified compression level.
 */
case class Http(
    _compressionLevel: Int = -1,
    _maxRequestSize: StorageUnit = 5.megabytes,
    _maxResponseSize: StorageUnit = 5.megabytes,
    _decompressionEnabled: Boolean = true,
    _channelBufferUsageTracker: Option[ChannelBufferUsageTracker] = None,
    _annotateCipherHeader: Option[String] = None,
    _enableTracing: Boolean = false,
    _maxInitialLineLength: StorageUnit = 4096.bytes,
    _maxHeaderSize: StorageUnit = 8192.bytes)
  extends CodecFactory[HttpRequest, HttpResponse]
{
  def compressionLevel(level: Int) = copy(_compressionLevel = level)
  def maxRequestSize(bufferSize: StorageUnit) = copy(_maxRequestSize = bufferSize)
  def maxResponseSize(bufferSize: StorageUnit) = copy(_maxResponseSize = bufferSize)
  def decompressionEnabled(yesno: Boolean) = copy(_decompressionEnabled = yesno)
  def channelBufferUsageTracker(usageTracker: ChannelBufferUsageTracker) =
    copy(_channelBufferUsageTracker = Some(usageTracker))
  def annotateCipherHeader(headerName: String) = copy(_annotateCipherHeader = Option(headerName))
  def enableTracing(enable: Boolean) = copy(_enableTracing = enable)
  def maxInitialLineLength(length: StorageUnit) = copy(_maxInitialLineLength = length)
  def maxHeaderSize(size: StorageUnit) = copy(_maxHeaderSize = size)

  def client = { config =>
    new Codec[HttpRequest, HttpResponse] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("httpCodec", new HttpClientCodec())
          pipeline.addLast(
            "httpDechunker",
            new HttpChunkAggregator(_maxResponseSize.inBytes.toInt))

          if (_decompressionEnabled)
            pipeline.addLast("httpDecompressor", new HttpContentDecompressor)

          pipeline
        }
      }

      override def newClientTransport(ch: Channel, statsReceiver: StatsReceiver): Transport[Any,Any] =
        new HttpTransport(super.newClientTransport(ch, statsReceiver))

      override def newClientDispatcher(transport: Transport[Any, Any]) =
        new DtabHttpDispatcher(transport)

      override def newTraceInitializer =
        if (_enableTracing) new HttpClientTraceInitializer[HttpRequest, HttpResponse]
        else TraceInitializerFilter.empty[HttpRequest, HttpResponse]
    }
  }

  def server = { config =>
    new Codec[HttpRequest, HttpResponse] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = Channels.pipeline()
          if (_channelBufferUsageTracker.isDefined) {
            pipeline.addLast(
              "channelBufferManager", new ChannelBufferManager(_channelBufferUsageTracker.get))
          }

          val maxRequestSizeInBytes = _maxRequestSize.inBytes.toInt
          val maxInitialLineLengthInBytes = _maxInitialLineLength.inBytes.toInt
          val maxHeaderSizeInBytes = _maxHeaderSize.inBytes.toInt
          pipeline.addLast("httpCodec", new SafeHttpServerCodec(maxInitialLineLengthInBytes, maxHeaderSizeInBytes, maxRequestSizeInBytes))

          if (_compressionLevel > 0) {
            pipeline.addLast("httpCompressor", new HttpContentCompressor(_compressionLevel))
          } else if (_compressionLevel == -1) {
            pipeline.addLast("httpCompressor", new TextualContentCompressor)
          }

          // Response to ``Expect: Continue'' requests.
          pipeline.addLast("respondToExpectContinue", new RespondToExpectContinue)
          pipeline.addLast(
            "httpDechunker",
            new HttpChunkAggregator(maxRequestSizeInBytes))

          _annotateCipherHeader foreach { headerName: String =>
            pipeline.addLast("annotateCipher", new AnnotateCipher(headerName))
          }

          pipeline
        }
      }

      override def newServerDispatcher(
        transport: Transport[Any, Any],
        service: Service[HttpRequest, HttpResponse]
      ): Closable =
        new HttpServerDispatcher(new HttpTransport(transport), service)

      override def prepareConnFactory(
        underlying: ServiceFactory[HttpRequest, HttpResponse]
      ): ServiceFactory[HttpRequest, HttpResponse] =
        DtabFilter.Netty andThen underlying

      override def newTraceInitializer =
        if (_enableTracing) new HttpServerTraceInitializer[HttpRequest, HttpResponse]
        else TraceInitializerFilter.empty[HttpRequest, HttpResponse]
    }
  }
}

object Http {
  def get() = new Http()
}

object HttpTracing {
  object Header {
    val TraceId = "X-B3-TraceId"
    val SpanId = "X-B3-SpanId"
    val ParentSpanId = "X-B3-ParentSpanId"
    val Sampled = "X-B3-Sampled"
    val Flags = "X-B3-Flags"

    val All = Seq(TraceId, SpanId, ParentSpanId, Sampled, Flags)
    val Required = Seq(TraceId, SpanId)
  }

  /**
   * Remove any parameters from url.
   */
  private[http] def stripParameters(uri: String): String = {
    uri.indexOf('?') match {
      case -1 => uri
      case n  => uri.substring(0, n)
    }
  }
}

private object TraceInfo {
  import HttpTracing._

  def letTraceIdFromRequestHeaders[R](request: HttpRequest)(f: => R): R = {
    val id = if (Header.Required.forall { request.headers.contains(_) }) {
      val spanId = SpanId.fromString(request.headers.get(Header.SpanId))

      spanId map { sid =>
        val traceId = SpanId.fromString(request.headers.get(Header.TraceId))
        val parentSpanId = SpanId.fromString(request.headers.get(Header.ParentSpanId))

        val sampled = Option(request.headers.get(Header.Sampled)) flatMap { sampled =>
          Try(sampled.toBoolean).toOption
        }

        val flags = getFlags(request)
        TraceId(traceId, parentSpanId, sid, sampled, flags)
      }
    } else if (request.headers.contains(Header.Flags)) {
      // even if there are no id headers we want to get the debug flag
      // this is to allow developers to just set the debug flag to ensure their
      // trace is collected
      Some(Trace.nextId.copy(flags = getFlags(request)))
    } else {
      Some(Trace.nextId)
    }

    // remove so the header is not visible to users
    Header.All foreach { request.headers.remove(_) }

    id match {
      case Some(id) =>
        Trace.letId(id) {
          traceRpc(request)
          f
        }
      case None =>
    traceRpc(request)
        f
  }
  }

  def setClientRequestHeaders(request: HttpRequest): Unit = {
    Header.All foreach { request.headers.remove(_) }

    request.headers.add(Header.TraceId, Trace.id.traceId.toString)
    request.headers.add(Header.SpanId, Trace.id.spanId.toString)
    // no parent id set means this is the root span
    Trace.id._parentId foreach { id =>
      request.headers.add(Header.ParentSpanId, id.toString)
    }
    // three states of sampled, yes, no or none (let the server decide)
    Trace.id.sampled foreach { sampled =>
      request.headers.add(Header.Sampled, sampled.toString)
    }
    request.headers.add(Header.Flags, Trace.id.flags.toLong)
    traceRpc(request)
  }

  def traceRpc(request: HttpRequest): Unit = {
    if (Trace.isActivelyTracing) {
      Trace.recordRpc(request.getMethod.getName)
      Trace.recordBinary("http.uri", stripParameters(request.getUri))
    }
  }

  /**
   * Safely extract the flags from the header, if they exist. Otherwise return empty flag.
   */
  def getFlags(request: HttpRequest): Flags = {
    try {
      Flags(Option(request.headers.get(Header.Flags)).map(_.toLong).getOrElse(0L))
    } catch {
      case _: Throwable => Flags()
    }
  }
}

private[finagle] class HttpServerTraceInitializer[Req <: HttpRequest, Rep]
  extends Stack.Module1[param.Tracer, ServiceFactory[Req, Rep]] {
  val role = TraceInitializerFilter.role
  val description = "Initialize the tracing system with trace info from the incoming request"
  def make(_tracer: param.Tracer, next: ServiceFactory[Req, Rep]) = {
    val param.Tracer(tracer) = _tracer
    val traceInitializer = Filter.mk[Req, Rep, Req, Rep] { (req, svc) =>
      Trace.letTracer(tracer) {
        TraceInfo.letTraceIdFromRequestHeaders(req) { svc(req) }
      }
    }
    traceInitializer andThen next
  }
}

private[finagle] class HttpClientTraceInitializer[Req <: HttpRequest, Rep]
  extends Stack.Module1[param.Tracer, ServiceFactory[Req, Rep]] {
  val role = TraceInitializerFilter.role
  val description = "Sets the next TraceId and attaches trace information to the outgoing request"
  def make(_tracer: param.Tracer, next: ServiceFactory[Req, Rep]) = {
    val param.Tracer(tracer) = _tracer
    val traceInitializer = Filter.mk[Req, Rep, Req, Rep] { (req, svc) =>
      Trace.letTracerAndNextId(tracer) {
        TraceInfo.setClientRequestHeaders(req)
        svc(req)
      }
    }
    traceInitializer andThen next
  }
}

/**
 * Pass along headers with the required tracing information.
 */
private[finagle] class HttpClientTracingFilter[Req <: HttpRequest, Res](serviceName: String)
  extends SimpleFilter[Req, Res]
{
  import HttpTracing._

  def apply(request: Req, service: Service[Req, Res]) = {
    TraceInfo.setClientRequestHeaders(request)
    service(request)
  }
}

/**
 * Adds tracing annotations for each http request we receive.
 * Including uri, when request was sent and when it was received.
 */
private[finagle] class HttpServerTracingFilter[Req <: HttpRequest, Res](serviceName: String)
  extends SimpleFilter[Req, Res]
{
  def apply(request: Req, service: Service[Req, Res]) = 
    TraceInfo.letTraceIdFromRequestHeaders(request) {
    service(request)
  }
}

/**
 * Ed. note: I'm not sure how parameterizing on REQUEST <: Request
 * works safely, ever. This is a big typesafety bug: the codec is
 * blindly casting Requests to REQUEST.
 *
 * Setting aggregateChunks to false disables the aggregator, and consequently
 * lifts the restriction on request size.
 *
 * @param httpFactory the underlying HTTP CodecFactory
 * @param aggregateChunks if true, the client pipeline collects HttpChunks into the body of each HttpResponse
 */
case class RichHttp[REQUEST <: Request](
  httpFactory: Http,
  aggregateChunks: Boolean = true
) extends CodecFactory[REQUEST, Response] {

  def client = { config =>
    new Codec[REQUEST, Response] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = httpFactory.client(config).pipelineFactory.getPipeline
          if (!aggregateChunks) pipeline.remove("httpDechunker")
          pipeline
        }
      }

      override def prepareServiceFactory(
        underlying: ServiceFactory[REQUEST, Response]
      ): ServiceFactory[REQUEST, Response] =
        underlying map(new DelayedReleaseService(_))

      override def prepareConnFactory(
        underlying: ServiceFactory[REQUEST, Response]
      ): ServiceFactory[REQUEST, Response] = {
        // Note: This is a horrible hack to ensure that close() calls from
        // ExpiringService do not propagate until all chunks have been read
        // Waiting on CSL-915 for a proper fix.
        underlying map(new DelayedReleaseService(_))
       }

      override def newClientTransport(ch: Channel, statsReceiver: StatsReceiver): Transport[Any,Any] =
        new HttpTransport(super.newClientTransport(ch, statsReceiver))

      override def newClientDispatcher(transport: Transport[Any, Any]) =
        new HttpClientDispatcher(transport)

      override def newTraceInitializer =
        if (httpFactory._enableTracing) new HttpClientTraceInitializer[REQUEST, Response]
        else TraceInitializerFilter.empty[REQUEST, Response]
    }
  }

  def server = { config =>
    new Codec[REQUEST, Response] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = httpFactory.server(config).pipelineFactory.getPipeline
          if (!aggregateChunks) pipeline.remove("httpDechunker")
          pipeline
        }
      }

      override def newServerDispatcher(
          transport: Transport[Any, Any],
          service: Service[REQUEST, Response]): Closable =
        new HttpServerDispatcher(new HttpTransport(transport), service)

      override def prepareConnFactory(
        underlying: ServiceFactory[REQUEST, Response]
      ): ServiceFactory[REQUEST, Response] =
        new DtabFilter.Finagle[REQUEST] andThen underlying

      override def newTraceInitializer =
        if (httpFactory._enableTracing) new HttpServerTraceInitializer[REQUEST, Response]
        else TraceInitializerFilter.empty[REQUEST, Response]
    }
  }
}
