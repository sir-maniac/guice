/**
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.inject.servlet;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Binding;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

/**
 * A wrapping dispatcher for servlets, in much the same way as {@link ManagedFilterPipeline} is for
 * filters.
 *
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
@Singleton
class ManagedServletPipeline {

  /** A request attribute that marks requests for servlets handles by guice */
  public static final String GUICE_MANAGED = "com.google.inject.servlet.guice_managed";

  /*
   * Request attributes with rrevious return values of underlying request.
   * In accordance with section 8.4.2 of the Servlet 2.4 specification.
   */
  public static final String FORWARD_REQUEST_URI = "javax.servlet.forward.request_uri";
  public static final String FORWARD_CONTEXT_PATH = "javax.servlet.forward.context_path";
  public static final String FORWARD_SERVLET_PATH = "javax.servlet.forward.servlet_path";
  public static final String FORWARD_PATH_INFO = "javax.servlet.forward.path_info";
  public static final String FORWARD_QUERY_STRING = "javax.servlet.forward.query_string";

  /*
   * Request attributes for a servlet include.
   * In accordance with section 8.3.1 of the Servlet 2.4 specification.
   */
  public static final String INCLUDE_REQUEST_URI = "javax.servlet.include.request_uri";
  public static final String INCLUDE_CONTEXT_PATH = "javax.servlet.include.context_path";
  public static final String INCLUDE_PATH_INFO = "javax.servlet.include.path_info";
  public static final String INCLUDE_SERVLET_PATH = "javax.servlet.include.servlet_path";
  public static final String INCLUDE_QUERY_STRING = "javax.servlet.include.query_string";

  private final ServletDefinition[] servletDefinitions;
  private static final TypeLiteral<ServletDefinition> SERVLET_DEFS =
      TypeLiteral.get(ServletDefinition.class);

  @Inject
  public ManagedServletPipeline(Injector injector) {
    this.servletDefinitions = collectServletDefinitions(injector);
  }

  boolean hasServletsMapped() {
    return servletDefinitions.length > 0;
  }

  /**
   * Introspects the injector and collects all instances of bound {@code List<ServletDefinition>}
   * into a master list.
   *
   * We have a guarantee that {@link com.google.inject.Injector#getBindings()} returns a map
   * that preserves insertion order in entry-set iterators.
   */
  private ServletDefinition[] collectServletDefinitions(Injector injector) {
    List<ServletDefinition> servletDefinitions = Lists.newArrayList();
    for (Binding<ServletDefinition> entry : injector.findBindingsByType(SERVLET_DEFS)) {
        servletDefinitions.add(entry.getProvider().get());
    }

    // Copy to a fixed size array for speed.
    return servletDefinitions.toArray(new ServletDefinition[servletDefinitions.size()]);
  }

  public void init(ServletContext servletContext, Injector injector) throws ServletException {
    Set<HttpServlet> initializedSoFar = Sets.newIdentityHashSet();

    for (ServletDefinition servletDefinition : servletDefinitions) {
      servletDefinition.init(servletContext, injector, initializedSoFar);
    }
  }

  public boolean service(ServletRequest request, ServletResponse response)
      throws IOException, ServletException {

    if (request instanceof HttpServletRequest)
    {
      final String path = ServletUtils.getContextRelativePath((HttpServletRequest)request);

      //stop at the first matching servlet and service
      for (ServletDefinition servletDefinition : servletDefinitions) {
        if (servletDefinition.shouldServe(path)) {

          HttpServletRequest requestToProcess =
              wrapForwardRequest((HttpServletRequest)request,
                  computePaths(servletDefinition,
                      (HttpServletRequest)request, path), false);

          requestToProcess.setAttribute(GUICE_MANAGED, Boolean.TRUE);
          try {
            servletDefinition.service(requestToProcess, response);
          } finally {
            requestToProcess.removeAttribute(GUICE_MANAGED);
            unwrapForwardRequest(requestToProcess);
          }
          return true;
        }
      }
    }

    //there was no match...
    return false;
  }

  public void destroy() {
    Set<HttpServlet> destroyedSoFar = Sets.newIdentityHashSet();
    for (ServletDefinition servletDefinition : servletDefinitions) {
      servletDefinition.destroy(destroyedSoFar);
    }
  }

  /**
   * @param path servlet context relative path, beginning with '/'
   *
   * @return Returns a request dispatcher wrapped with a servlet mapped to
   * the given path or null if no mapping was found.
   */
  RequestDispatcher getRequestDispatcher(final String path) {
    // TODO(dhanji): check servlet spec to see if the following is legal or not.
    // Need to strip query string if requested...

    final String contextRelativePath = ServletUtils.removePathParam(path);

    for (final ServletDefinition servletDefinition : servletDefinitions) {
      if (servletDefinition.shouldServe(contextRelativePath)) {
        return new RequestDispatcher() {
          public void forward(ServletRequest servletRequest, ServletResponse servletResponse)
              throws ServletException, IOException {
            Preconditions.checkState(!servletResponse.isCommitted(),
                "Response has been committed--you can only call forward before"
                + " committing the response (hint: don't flush buffers)");

            // clear buffer before forwarding
            servletResponse.resetBuffer();

            ServletRequest requestToProcess;
            if (servletRequest instanceof HttpServletRequest) {

               requestToProcess = wrapForwardRequest((HttpServletRequest)servletRequest,
                   computePaths(servletDefinition,
                       (HttpServletRequest)servletRequest, contextRelativePath), true);
            } else {
              // This should never happen, but instead of throwing an exception
              // we will allow a happy case pass thru for maximum tolerance to
              // legacy (and internal) code.
              requestToProcess = servletRequest;
            }

            // now dispatch to the servlet
            try {
              doServiceImpl(servletDefinition, requestToProcess, servletResponse);
            } finally {
              if (servletRequest instanceof HttpServletRequest) {
                unwrapForwardRequest((HttpServletRequest)requestToProcess);
              }
            }
          }

          public void include(ServletRequest servletRequest, ServletResponse servletResponse)
              throws ServletException, IOException {

            ServletRequest requestToProcess;
            RequestPaths paths = null;
            if (servletRequest instanceof HttpServletRequest) {
              paths = computePaths(servletDefinition,
                  (HttpServletRequest)servletRequest, contextRelativePath);
              requestToProcess = wrapIncludeRequest((HttpServletRequest)servletRequest, paths);
            } else {
              // This should never happen, but instead of throwing an exception
              // we will allow a happy case pass thru for maximum tolerance to
              // legacy (and internal) code.
              requestToProcess = servletRequest;
            }

            // route to the target servlet
            try {
              doServiceImpl(servletDefinition, requestToProcess, servletResponse);
            } finally {
              if (servletRequest instanceof HttpServletRequest) {
                unwrapIncludeRequest((HttpServletRequest)requestToProcess, paths);
              }
            }
          }

          private void doServiceImpl(ServletDefinition servletDefinition, ServletRequest servletRequest,
              ServletResponse servletResponse) throws ServletException, IOException {
            servletDefinition.doService((HttpServletRequest)servletRequest, (HttpServletResponse)servletResponse);
          }
        };
      }
    }

    //otherwise, can't process
    return null;
  }

  // visible for testing
  static class RequestPaths {
    public final String requestURI;
    public final String servletPath;
    public final String pathInfo;

    public RequestPaths(String requestURI, String servletPath, String pathInfo) {
      super();
      this.requestURI = requestURI;
      this.servletPath = servletPath;
      this.pathInfo = pathInfo;
    }
  }

  private static String computePathInfo(final String contextRelativePath, String newServletPath) {
    int servletPathLength = newServletPath.length();
    String newPathInfo = contextRelativePath.replaceAll("[/]{2,}", "/");
    // See: https://github.com/google/guice/issues/372
    if (newPathInfo.startsWith(newServletPath)) {
      newPathInfo = newPathInfo.substring(servletPathLength);

      // Corner case: when servlet path & request path match exactly (without trailing '/'),
      // then pathinfo is null.
      if (newPathInfo.isEmpty() && servletPathLength > 0) {
        newPathInfo = null;
      } else {
        try {
          newPathInfo = new URI(newPathInfo).getPath();
        } catch (URISyntaxException e) {
          // ugh, just leave it alone then
        }
      }
    } else {
      newPathInfo = null; // we know nothing additional about the URI.
    }

    return newPathInfo;
  }

  // visible for testing
  static RequestPaths computePaths(ServletDefinition servletDefinition,
            HttpServletRequest request, String contextRelativePath) {
    contextRelativePath = ServletUtils.removePathParam(contextRelativePath);

    String newPathInfo, newServletPath;

    newServletPath = servletDefinition.extractPath(contextRelativePath);
    if (null == newServletPath) {
      newServletPath = contextRelativePath;
    }
    newPathInfo = computePathInfo(contextRelativePath, newServletPath);

    return new RequestPaths(request.getContextPath() + contextRelativePath, newServletPath, newPathInfo);
  }

  protected static boolean eq(String str1, String str2) {
    return str1 == null ? str2 == null : str1.equals(str2);
  }


  /**
   * Wrap a request to partially support calls to RequestDispatcher#forward
   *
   * <p>According to SRV.6.2.2, the outer wrapper shouldn't change by the what
   *    the servlet has seen, so containers wrap beneath other wrappers on calls to
   *    forward() and include().  We mimic that behavior here.
   *
   * <p>NOTE: for simplicity this does not fully comply to the servlet specification;
   *   it does not merge arguments that may be passed to the new URI.
   *
   * @param request
   * @param newPath
   * @return
   */
  // visible for testing
  static HttpServletRequest wrapForwardRequest(HttpServletRequest request,
      RequestPaths paths, boolean forward) {

    HttpServletRequest top = request;
    HttpServletRequestWrapper previous = null;
    HttpServletRequest current = top;

    while (current instanceof HttpServletRequestWrapper &&
          !(current instanceof ForwardRequestWrapper)) {
      previous = (HttpServletRequestWrapper)current;
      current = (HttpServletRequest) previous.getRequest();
    }

    ForwardRequestWrapper wrapper;
    if (current instanceof ForwardRequestWrapper) {
      wrapper = (ForwardRequestWrapper) current;
      wrapper.reuse(paths.requestURI, paths.servletPath, paths.pathInfo);
    } else {
      wrapper = new ForwardRequestWrapper(current,
            paths.requestURI, paths.servletPath, paths.pathInfo);

      if (forward) {
        // save the original path information to comply with SRV.8.4.2 of the servlet specification
        wrapper.setAttribute(FORWARD_REQUEST_URI, top.getRequestURI());
        wrapper.setAttribute(FORWARD_CONTEXT_PATH, top.getContextPath());
        wrapper.setAttribute(FORWARD_PATH_INFO, top.getPathInfo());
        wrapper.setAttribute(FORWARD_QUERY_STRING, top.getQueryString());
        wrapper.setAttribute(FORWARD_SERVLET_PATH, top.getServletPath());
      }

      if (previous != null) {
        previous.setRequest(wrapper);
      }else {
        top = wrapper;
      }
    }

    return top;
  }

  /**
   * Wrap a request to partially support calls to RequestDispatcher#include
   *
   * <p>According to SRV.6.2.2, the outer wrapper shouldn't change by the what
   *    the servlet has seen, so containers wrap beneath other wrappers on calls to
   *    forward() and include().  We mimic that behavior here.
   *
   * <p>NOTE: for simplicity this does not fully comply to the servlet specification;
   *   it does not merge arguments that may be passed to the new URI.
   *
   * @param request
   * @param newPath
   * @return
   */
  // visible for testing
  private static HttpServletRequest wrapIncludeRequest(HttpServletRequest request, RequestPaths paths) {

    HttpServletRequest top = request;
    HttpServletRequestWrapper previous = null;
    HttpServletRequest current = top;

    while (current instanceof HttpServletRequestWrapper) {
      previous = (HttpServletRequestWrapper)current;
      current = (HttpServletRequest) previous.getRequest();
    }

    IncludeRequestWrapper wrapper = new IncludeRequestWrapper(current);

    if (previous != null) {
      previous.setRequest(wrapper);
    } else {
      top = wrapper;
    }
    // save the path information in request attributes.
    // In compliance to SRV.8.3.1 of the servlet specification
    wrapper.setAttribute(INCLUDE_REQUEST_URI, paths.requestURI);
    wrapper.setAttribute(INCLUDE_CONTEXT_PATH, current.getContextPath());
    wrapper.setAttribute(INCLUDE_PATH_INFO, paths.pathInfo);
    wrapper.setAttribute(INCLUDE_QUERY_STRING, current.getQueryString());
    wrapper.setAttribute(INCLUDE_SERVLET_PATH, paths.servletPath);

    return top;
  }


  private static HttpServletRequest unwrapForwardRequest(HttpServletRequest request) {
    HttpServletRequest top = request;
    HttpServletRequestWrapper previous = null;
    HttpServletRequest current = top;

    // unwrap the first forward request wrapper found
    while (current instanceof HttpServletRequestWrapper) {
      if (current.getClass() == ForwardRequestWrapper.class) {
        if (previous != null) {
          previous.setRequest(((RequestDispatcherRequestWrapper) current).getRequest());
        } else {
          top = (HttpServletRequest)((RequestDispatcherRequestWrapper) current).getRequest();
        }
        break;
      }

      previous = (HttpServletRequestWrapper)current;
      current = (HttpServletRequest) previous.getRequest();
    }

    return top;
  }

  private static HttpServletRequest unwrapIncludeRequest(HttpServletRequest request, RequestPaths paths) {
    HttpServletRequest top = request;
    HttpServletRequestWrapper previous = null;
    HttpServletRequest current = top;

    // unwrap the first include wrapper that matches the requestURI
    while (current instanceof HttpServletRequestWrapper) {
      if (current instanceof IncludeRequestWrapper &&
          eq(paths.requestURI, (String)current.getAttribute(INCLUDE_REQUEST_URI))) {
        if (previous != null) {
          previous.setRequest(((RequestDispatcherRequestWrapper) current).getRequest());
        } else {
          top = (HttpServletRequest)((RequestDispatcherRequestWrapper) current).getRequest();
        }
        break;
      }

      previous = (HttpServletRequestWrapper)current;
      current = (HttpServletRequest) previous.getRequest();
    }

    return top;
  }


  /**
   * A HttpServletRequestWrapper supporting a subset of the servlet standard to
   *   partially support calls to RequestDispatcher#forward
   *
   * <p>The following request attributes are isolated from the underlying request.
   *
   * <p>The request attributes are from servlet specification SRV.8.4.2
   * <ul>
   *  <li>javax.servlet.forward.request_uri</li>
   *  <li>javax.servlet.forward.context_path</li>
   *  <li>javax.servlet.forward.servlet_path</li>
   *  <li>javax.servlet.forward.path_info</li>
   *  <li>javax.servlet.forward.query_string</li>
   * </ul>
   *
   */
  private static class ForwardRequestWrapper extends RequestDispatcherRequestWrapper {
    private String origRequestURI;
    private String origServletPath;
    private String origPathInfo;

    private String newServletPath;
    private String newRequestURI;
    private String newPathInfo;

    public ForwardRequestWrapper(HttpServletRequest servletRequest,
        String newRequestURI, String newServletPath, String newPathInfo) {
      super(servletRequest);

      this.origPathInfo = super.getPathInfo();
      this.origRequestURI = super.getRequestURI();
      this.origServletPath = super.getServletPath();

      this.newServletPath = newServletPath;
      this.newPathInfo = newPathInfo;
      this.newRequestURI = newRequestURI;
    }

    @Override
    public String getPathInfo() {
      String innerPathInfo = super.getPathInfo();
      if (!eq(origPathInfo, innerPathInfo)) {
        return innerPathInfo;
      }

      return newPathInfo;
    }

    public String getReplacedRequestURI() {
      return newRequestURI;
    }

    @Override
    public String getRequestURI() {
      // if inner value changes, let it override us
      String innerRequestURI = super.getRequestURI();
      if (!eq(origRequestURI, innerRequestURI))
        return innerRequestURI;

      return newRequestURI;
    }

    @Override
    public StringBuffer getRequestURL() {
      // if inner value changes, let it override us
      if (!eq(origRequestURI, super.getRequestURI()))
        return super.getRequestURL();

      StringBuffer url = new StringBuffer();
      String scheme = getScheme();
      int port = getServerPort();

      url.append(scheme);
      url.append("://");
      url.append(getServerName());
      // port might be -1 in some cases (see java.net.URL.getPort)
      if (port > 0 &&
          (("http".equals(scheme) && (port != 80)) ||
           ("https".equals(scheme) && (port != 443)))) {
        url.append(':');
        url.append(port);
      }
      url.append(getRequestURI());

      return (url);
    }

    @Override
    public String getServletPath() {
      // if inner wrapper changes, let it override us
      String innerServletPath = super.getServletPath();
      if (!eq(origServletPath, innerServletPath)) {
        return innerServletPath;
      }

      return newServletPath;
    }

    /**
     * Allow the reuse of an existing forward wrapper by simply updating
     *   the replacement values
     *
     */
    public void reuse(String newRequestURI, String newServletPath, String newPathInfo) {
      this.newServletPath = newServletPath;
      this.newPathInfo = newPathInfo;
      this.newRequestURI = newRequestURI;
    }
  }

  private static class IncludeRequestWrapper extends RequestDispatcherRequestWrapper {
    public IncludeRequestWrapper(HttpServletRequest request) {
      super(request);
    }
  }

  /**
   * A HttpServletRequestWrapper supporting a subset of the servlet standard to
   *   partially support calls to RequestDispatcher#forward and RequestDispatcher#include
   *
   * <p>The following request attributes are isolated from the underlying request in order
   *  ease unwrapping, referenced from servlet specification SRV.8.4.2 and SRV.8.3.1
   * <ul>
   *  <li>javax.servlet.include.request_uri</li>
   *  <li>javax.servlet.include.context_path</li>
   *  <li>javax.servlet.include.servlet_path</li>
   *  <li>javax.servlet.include.path_info</li>
   *  <li>javax.servlet.include.query_string</li>
   *  <li>javax.servlet.forward.request_uri</li>
   *  <li>javax.servlet.forward.context_path</li>
   *  <li>javax.servlet.forward.servlet_path</li>
   *  <li>javax.servlet.forward.path_info</li>
   *  <li>javax.servlet.forward.query_string</li>
   * </ul>
   *
   */

  private static class RequestDispatcherRequestWrapper extends HttpServletRequestWrapper {

    private static final Set<String> SPECIAL_ATTRIBUTES = ImmutableSet.of(
                FORWARD_REQUEST_URI,
                FORWARD_CONTEXT_PATH,
                FORWARD_PATH_INFO,
                FORWARD_QUERY_STRING,
                FORWARD_SERVLET_PATH,
                INCLUDE_REQUEST_URI,
                INCLUDE_CONTEXT_PATH,
                INCLUDE_PATH_INFO,
                INCLUDE_QUERY_STRING,
                INCLUDE_SERVLET_PATH);

    private final Map<String, Object> origSpecials = new HashMap<String, Object>();
    private final Map<String, Object> specialAttributes = new HashMap<String, Object>();

    public RequestDispatcherRequestWrapper(HttpServletRequest request) {
      super(request);

      // save a copy to detect changes to possible inner wrappers
      for (String specialName : SPECIAL_ATTRIBUTES) {
        Object specialVal = super.getAttribute(specialName);
        if (specialVal != null) {
          origSpecials.put(specialName, specialVal);
        }
      }
    }

    @Override
    public Object getAttribute(String name) {
      if (SPECIAL_ATTRIBUTES.contains(name)) {
        // An inner wrapper change removes this wrapper's effects
        String innerValue = (String)super.getAttribute(name);
        if (!eq(innerValue, (String)origSpecials.get(name))) {
          return innerValue;
        }

        return specialAttributes.get(name);
      } else {
        return super.getAttribute(name);
      }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public Enumeration getAttributeNames() {
      /*
       * Attempts to efficiently provide an enumeration for inner attributes, and
       *  their masked special attributes
       */

      Iterator<String> innerAttribs = Iterators.<String>filter(
          Iterators.forEnumeration(super.getAttributeNames()),
          new Predicate<String>() {
            @Override
            public boolean apply(String input) {
              // remove special attributes since they are handled below
              return !SPECIAL_ATTRIBUTES.contains(input);
            }
          });

      Iterator<String> specials = Iterators.<String>filter(
          specialAttributes.keySet().iterator(),
          new Predicate<String>() {
            @Override
            public boolean apply(String input) {
              // An inner wrapper change removes this wrapper's effects
              String innerVal = (String)RequestDispatcherRequestWrapper.super.getAttribute(input);
              if (!eq(innerVal, (String)origSpecials.get(input))) {
                return innerVal != null;
              }
              return true;
            }
          });

      return Iterators.asEnumeration(
          Iterators.concat(specials, innerAttribs)
          );
    }

    @Override
    public void removeAttribute(String name) {
      if (SPECIAL_ATTRIBUTES.contains(name)) {
        // An inner wrapper change removes this wrapper's effects
        String innerValue = (String)super.getAttribute(name);
        if (!eq(innerValue, (String)origSpecials.get(name))) {
          super.removeAttribute(name);
        }

        specialAttributes.remove(name);
      } else {
        super.removeAttribute(name);
      }
    }

    @Override
    public void setAttribute(String name, Object o) {
      if (SPECIAL_ATTRIBUTES.contains(name)) {
        // An inner wrapper change removes this wrapper's effects
        String innerValue = (String)super.getAttribute(name);
        if (!eq(innerValue, (String)origSpecials.get(name))) {
          super.removeAttribute(name);
        }

        specialAttributes.put(name, o);
      } else {
        super.setAttribute(name, o);
      }
    }

  }
}
