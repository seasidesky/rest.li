package com.linkedin.restli.server;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.callback.CallbackAdapter;
import com.linkedin.data.DataMap;
import com.linkedin.parseq.Engine;
import com.linkedin.r2.message.RequestContext;
import com.linkedin.r2.message.rest.RestException;
import com.linkedin.r2.message.rest.RestRequest;
import com.linkedin.r2.message.rest.RestResponse;
import com.linkedin.r2.transport.common.RestRequestHandler;
import com.linkedin.restli.common.HttpStatus;
import com.linkedin.restli.internal.server.RoutingResult;
import com.linkedin.restli.internal.server.model.ResourceModel;
import com.linkedin.restli.internal.server.response.ErrorResponseBuilder;
import com.linkedin.restli.internal.server.response.ResponseUtils;
import com.linkedin.restli.internal.server.response.RestLiResponse;
import com.linkedin.restli.internal.server.response.RestLiResponseException;
import com.linkedin.restli.internal.server.util.DataMapUtils;
import com.linkedin.restli.server.multiplexer.MultiplexedRequestHandlerImpl;
import com.linkedin.restli.server.resources.ResourceFactory;
import com.linkedin.restli.server.util.UnstructuredDataUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A Rest.li server that handles the fully buffered {@link RestRequest}.
 *
 * @author Nick Dellamaggiore
 * @author Xiao Ma
 */
class RestRestLiServer extends BaseRestLiServer implements RestRequestHandler
{
  private static final Logger log = LoggerFactory.getLogger(RestRestLiServer.class);

  private final List<NonResourceRequestHandler> _nonResourceRequestHandlers;

  RestRestLiServer(RestLiConfig config,
      ResourceFactory resourceFactory,
      Engine engine,
      Map<String, ResourceModel> rootResources,
      ErrorResponseBuilder errorResponseBuilder)
  {
    super(config,
        resourceFactory,
        engine,
        rootResources,
        errorResponseBuilder);

    _nonResourceRequestHandlers = new ArrayList<>();

    // Add documentation request handler
    RestLiDocumentationRequestHandler docReqHandler = config.getDocumentationRequestHandler();
    if (docReqHandler != null)
    {
      docReqHandler.initialize(config, rootResources);
      _nonResourceRequestHandlers.add(docReqHandler);
    }

    // Add multiplexed request handler
    _nonResourceRequestHandlers.add(new MultiplexedRequestHandlerImpl(this,
        engine,
        config.getMaxRequestsMultiplexed(),
        config.getMultiplexedIndividualRequestHeaderWhitelist(),
        config.getMultiplexerSingletonFilter(),
        config.getMultiplexerRunMode(),
        errorResponseBuilder));

    // Add debug request handlers
    config.getDebugRequestHandlers().stream()
        .map(handler -> new DelegatingDebugRequestHandler(handler, this))
        .forEach(_nonResourceRequestHandlers::add);
  }

  List<NonResourceRequestHandler> getNonResourceRequestHandlers()
  {
    return _nonResourceRequestHandlers;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void handleRequest(final RestRequest request, final RequestContext requestContext,
      final Callback<RestResponse> callback)
  {
    try
    {
      doHandleRequest(request, requestContext, callback);
    }
    catch (Exception e)
    {
      log.error("Uncaught exception", e);
      callback.onError(e);
    }
  }

  protected void doHandleRequest(final RestRequest request,
      final RequestContext requestContext,
      final Callback<RestResponse> callback)
  {
    Optional<NonResourceRequestHandler> nonResourceRequestHandler = _nonResourceRequestHandlers.stream()
        .filter(handler -> handler.shouldHandle(request))
        .findFirst();

    // TODO: Use Optional#ifPresentOrElse once we are on Java 9.
    if (nonResourceRequestHandler.isPresent())
    {
      nonResourceRequestHandler.get().handleRequest(request, requestContext, callback);
    }
    else
    {
      handleResourceRequest(request, requestContext, callback);
    }
  }

  void handleResourceRequest(RestRequest request, RequestContext requestContext, Callback<RestResponse> callback)
  {
    RoutingResult routingResult;
    try
    {
      routingResult = getRoutingResult(request, requestContext);
    }
    catch (Exception e)
    {
      callback.onError(buildPreRoutingRestException(e, request));
      return;
    }

    handleResourceRequest(request, routingResult, callback);
  }

  private RestException buildPreRoutingRestException(Throwable throwable, RestRequest request)
  {
    RestLiResponseException restLiException = buildPreRoutingError(throwable, request);
    return ResponseUtils.buildRestException(restLiException);
  }

  protected void handleResourceRequest(RestRequest request,
      RoutingResult routingResult,
      Callback<RestResponse> callback)
  {
    DataMap entityDataMap = null;
    if (request.getEntity() != null && request.getEntity().length() > 0)
    {
      if (UnstructuredDataUtil.isUnstructuredDataRouting(routingResult))
      {
        callback.onError(new RoutingException("Unstructured Data is not supported in non-streaming Rest.li server", HttpStatus.S_400_BAD_REQUEST.getCode()));
        return;
      }
      try
      {
        entityDataMap = DataMapUtils.readMapWithExceptions(request);
      }
      catch (IOException e)
      {
        callback.onError(new RoutingException("Cannot parse request entity", HttpStatus.S_400_BAD_REQUEST.getCode(), e));
      }
    }

    handleResourceRequest(request,
        routingResult,
        entityDataMap,
        new RestLiToRestResponseCallbackAdapter(callback, routingResult));
  }

  static class RestLiToRestResponseCallbackAdapter extends CallbackAdapter<RestResponse, RestLiResponse>
  {
    private final RoutingResult _routingResult;

    RestLiToRestResponseCallbackAdapter(Callback<RestResponse> callback, RoutingResult routingResult)
    {
      super(callback);
      _routingResult = routingResult;
    }

    @Override
    protected RestResponse convertResponse(RestLiResponse restLiResponse)
          throws Exception
    {
      return ResponseUtils.buildResponse(_routingResult, restLiResponse);
    }

    @Override
    protected Throwable convertError(Throwable error)
    {
      return error instanceof RestLiResponseException
          ? ResponseUtils.buildRestException((RestLiResponseException) error)
          : error;
    }
  }
}
