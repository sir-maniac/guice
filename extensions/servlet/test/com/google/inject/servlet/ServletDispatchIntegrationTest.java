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

import static com.google.inject.servlet.ManagedServletPipeline.GUICE_MANAGED;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Singleton;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Tests the FilterPipeline that dispatches to guice-managed servlets,
 * is a full integration test, with a real injector.
 *
 * @author Dhanji R. Prasanna (dhanji gmail com)
 */
public class ServletDispatchIntegrationTest extends TestCase {
  private static int inits, services, destroys, doFilters;

  @Override
  public void setUp() {
    inits = 0;
    services = 0;
    destroys = 0;
    doFilters = 0;

    GuiceFilter.reset();
  }

  public final void testDispatchRequestToManagedPipelineServlets()
      throws ServletException, IOException {
    final Injector injector = Guice.createInjector(new ServletModule() {

      @Override
      protected void configureServlets() {
        serve("/*").with(TestServlet.class);

        // These servets should never fire... (ordering test)
        serve("*.html").with(NeverServlet.class);
        serve("/test/*").with(Key.get(NeverServlet.class));
        serve("/index/*").with(Key.get(NeverServlet.class));
        serve("*.jsp").with(Key.get(NeverServlet.class));
      }
    });

    final FilterPipeline pipeline = injector.getInstance(FilterPipeline.class);

    pipeline.initPipeline(null);

    //create ourselves a mock request with test URI
    HttpServletRequest requestMock = createMock(HttpServletRequest.class);

    ServletTestUtils.expectRequest(requestMock, "/index.html", "/index.html", null, "");

    expect(requestMock.getRequestURL()).andReturn(new StringBuffer("/index.html")).anyTimes();
    expect(requestMock.getQueryString()).andReturn(null).anyTimes();

    //dispatch request
    replay(requestMock);

    pipeline.dispatch(requestMock, null, createMock(FilterChain.class));

    pipeline.destroyPipeline();

    verify(requestMock);

    assertTrue("lifecycle states did not fire correct number of times-- inits: " + inits + "; dos: "
            + services + "; destroys: " + destroys,
        inits == 2 && services == 1 && destroys == 2);
  }

  public final void testDispatchRequestToManagedPipelineWithFilter()
      throws ServletException, IOException {
    final Injector injector = Guice.createInjector(new ServletModule() {

      @Override
      protected void configureServlets() {
        filter("/*").through(TestFilter.class);

        serve("/*").with(TestServlet.class);

        // These servets should never fire...
        serve("*.html").with(NeverServlet.class);
        serve("/test/*").with(Key.get(NeverServlet.class));
        serve("/index/*").with(Key.get(NeverServlet.class));
        serve("*.jsp").with(Key.get(NeverServlet.class));

      }
    });

    final FilterPipeline pipeline = injector.getInstance(FilterPipeline.class);

    pipeline.initPipeline(null);

    //create ourselves a mock request with test URI
    HttpServletRequest requestMock = createMock(HttpServletRequest.class);

    ServletTestUtils.expectRequest(requestMock, "/index.html", "/index.html", null, "");

    expect(requestMock.getRequestURL()).andReturn(new StringBuffer("/index.html")).anyTimes();
    expect(requestMock.getQueryString()).andReturn(null).anyTimes();

    //dispatch request
    replay(requestMock);

    pipeline.dispatch(requestMock, null, createMock(FilterChain.class));

    pipeline.destroyPipeline();

    verify(requestMock);

    assertTrue("lifecycle states did not fire correct number of times-- inits: " + inits + "; dos: "
            + services + "; destroys: " + destroys + "; doFilters: " + doFilters,
        inits == 3 && services == 1 && destroys == 3 && doFilters == 1);
  }

  @Singleton
  public static class TestServlet extends HttpServlet {
    public void init(ServletConfig filterConfig) throws ServletException {
      inits++;
    }

    public void service(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IOException, ServletException {
      services++;
    }

    public void destroy() {
      destroys++;
    }
  }

  @Singleton
  public static class NeverServlet extends HttpServlet {
    public void init(ServletConfig filterConfig) throws ServletException {
      inits++;
    }

    public void service(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IOException, ServletException {
      fail("NeverServlet was fired, when it should not have been.");
    }

    public void destroy() {
      destroys++;
    }
  }

  @Singleton
  public static class TestFilter implements Filter {
    public void init(FilterConfig filterConfig) throws ServletException {
      inits++;
    }

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
        FilterChain filterChain) throws IOException, ServletException {
      doFilters++;
      filterChain.doFilter(servletRequest, servletResponse);
    }

    public void destroy() {
      destroys++;
    }
  }


  @Singleton
  public static class ForwardingServlet extends HttpServlet {
    public void service(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IOException, ServletException {
      final HttpServletRequest request = (HttpServletRequest) servletRequest;

      request.getRequestDispatcher("/blah.jsp")
          .forward(servletRequest, servletResponse);
    }
  }

  @Singleton
  public static class ForwardedServlet extends HttpServlet {
    static int forwardedTo = 0;

    // Reset for test.
    public ForwardedServlet() {
      forwardedTo = 0;
    }

    public void service(ServletRequest servletRequest, ServletResponse servletResponse)
        throws IOException, ServletException {
      final HttpServletRequest request = (HttpServletRequest) servletRequest;

      assertNotNull(request.getAttribute(GUICE_MANAGED));
      forwardedTo++;
    }
  }

  public void testForwardUsingRequestDispatcher() throws IOException, ServletException {
    Guice.createInjector(new ServletModule() {
      @Override
      protected void configureServlets() {
        serve("/").with(ForwardingServlet.class);
        serve("/blah.jsp").with(ForwardedServlet.class);
      }
    });

    final HttpServletRequest requestMock = createMock(HttpServletRequest.class);
    HttpServletResponse responseMock = createMock(HttpServletResponse.class);

    ServletTestUtils.expectRequest(requestMock, "/", "/", null, "");

    expect(requestMock.getRequestURL()).andReturn(new StringBuffer("/")).anyTimes();
    expect(requestMock.getQueryString()).andReturn(null).anyTimes();

    expect(responseMock.isCommitted()).andReturn(false);
    responseMock.resetBuffer();

    replay(requestMock, responseMock);

    new GuiceFilter()
        .doFilter(requestMock, responseMock,
            createMock(FilterChain.class));

    assertEquals("Incorrect number of forwards", 1, ForwardedServlet.forwardedTo);
    verify(requestMock, responseMock);
  }

  public final void testQueryInRequestUri_regex() throws Exception {
    final Injector injector = Guice.createInjector(new ServletModule() {

      @Override
      protected void configureServlets() {
        filterRegex("(.)*\\.html").through(TestFilter.class);

        serveRegex("(.)*\\.html").with(TestServlet.class);
      }
    });

    final FilterPipeline pipeline = injector.getInstance(FilterPipeline.class);

    pipeline.initPipeline(null);

    //create ourselves a mock request with test URI
    HttpServletRequest requestMock = createMock(HttpServletRequest.class);

    ServletTestUtils.expectRequest(requestMock, "/index.html?query=params", "/index.html", null, "");

    expect(requestMock.getRequestURL()).andReturn(new StringBuffer("/index.html")).anyTimes();
    expect(requestMock.getQueryString()).andReturn(null).anyTimes();

    //dispatch request
    replay(requestMock);

    pipeline.dispatch(requestMock, null, createMock(FilterChain.class));

    pipeline.destroyPipeline();

    verify(requestMock);

    assertEquals(1, doFilters);
    assertEquals(1, services);
  }

  public final void testQueryInRequestUri() throws Exception {
    final Injector injector = Guice.createInjector(new ServletModule() {

      @Override
      protected void configureServlets() {
        filter("/index.html").through(TestFilter.class);

        serve("/index.html").with(TestServlet.class);
      }
    });

    final FilterPipeline pipeline = injector.getInstance(FilterPipeline.class);

    pipeline.initPipeline(null);

    //create ourselves a mock request with test URI
    HttpServletRequest requestMock = createMock(HttpServletRequest.class);

    ServletTestUtils.expectRequest(requestMock, "/index.html?query=params", "/index.html", null, "");

    expect(requestMock.getRequestURL()).andReturn(new StringBuffer("/index.html")).anyTimes();
    expect(requestMock.getQueryString()).andReturn(null).anyTimes();

    //dispatch request
    replay(requestMock);

    pipeline.dispatch(requestMock, null, createMock(FilterChain.class));

    pipeline.destroyPipeline();

    verify(requestMock);

    assertEquals(1, doFilters);
    assertEquals(1, services);
  }
}
