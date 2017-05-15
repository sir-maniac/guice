// Copyright 2011 Google Inc. All Rights Reserved.

package com.google.inject.servlet;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.easymock.IAnswer;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.primitives.Booleans;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.easymock.EasyMock.replay;

/**
 * Utilities for servlet tests.
 * 
 * @author sameb@google.com (Sam Berlin)
 */
public class ServletTestUtils {
  
  private ServletTestUtils() {}

  private static class ThrowingInvocationHandler implements InvocationHandler {
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      throw new AssertionError("Unexpected call to " + method.toString());
    }
  }
  
  /**
   * Returns a FilterChain that does nothing.
   */
  public static FilterChain newNoOpFilterChain() {
    return new FilterChain() {
      public void doFilter(ServletRequest request, ServletResponse response) {
      }
    };
  }
  
  /**
   * Returns a fake, HttpServletRequest which stores attributes in a HashMap.
   */
  @SuppressWarnings("rawtypes")
  public static HttpServletRequest newFakeHttpServletRequest() {

    HttpServletRequest delegate = createMock(HttpServletRequest.class);

    final HashMap<String, Object> attrs = Maps.newHashMap();
    final HttpSession session = newFakeHttpSession();

    expect(delegate.getMethod()).andReturn("GET").anyTimes();
    expect(delegate.getPathInfo()).andReturn(null).anyTimes();
    expect(delegate.getRequestURI()).andReturn("/").anyTimes();
    expect(delegate.getServletPath()).andReturn("").anyTimes();
    expect(delegate.getContextPath()).andReturn("").anyTimes();
    expect(delegate.getQueryString()).andReturn("").anyTimes();
    expect(delegate.getSession()).andReturn(session).anyTimes();
    expect(delegate.getParameterMap()).andReturn(ImmutableMap.of()).anyTimes();

    expect(delegate.getAttributeNames()).andAnswer(new IAnswer<Enumeration>() {
      @Override
      public Enumeration answer() throws Throwable {
        return Collections.enumeration(attrs.keySet());
      }
    }).anyTimes();
    expect(delegate.getAttribute(anyObject(String.class))).andAnswer(new IAnswer<Object>() {
      @Override
      public Object answer() throws Throwable {
        return attrs.get(getCurrentArguments()[0]);
      }
    }).anyTimes();
    delegate.setAttribute(anyObject(String.class), anyObject());
    expectLastCall().andAnswer(new IAnswer<Void>() {
      @Override
      public Void answer() throws Throwable {
        attrs.put((String)getCurrentArguments()[0], getCurrentArguments()[1]);
        return null;
      }
    }).anyTimes();
    delegate.removeAttribute(anyObject(String.class));
    expectLastCall().andAnswer(new IAnswer<Void>() {
      @Override
      public Void answer() throws Throwable {
        attrs.remove((String)getCurrentArguments()[0]);
        return null;
      }
    }).anyTimes();
    replay(delegate);

    return delegate;

  }
  
  /**
   * Returns a fake, HttpServletResponse which throws an exception if any of its
   * methods are called.
   */
  public static HttpServletResponse newFakeHttpServletResponse() {
    return (HttpServletResponse) Proxy.newProxyInstance(
        HttpServletResponse.class.getClassLoader(),
        new Class[] { HttpServletResponse.class }, new ThrowingInvocationHandler());
  }  
  
  private static class FakeHttpSessionHandler implements InvocationHandler, Serializable {
    final Map<String, Object> attributes = Maps.newHashMap();

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      String name = method.getName();
      if ("setAttribute".equals(name)) {
        attributes.put((String) args[0], args[1]);
        return null;
      } else if ("getAttribute".equals(name)) {
        return attributes.get(args[0]);
      } else {
        throw new UnsupportedOperationException();
      }
    }
  }

  /**
   * Returns a fake, serializable HttpSession which stores attributes in a HashMap.
   */
  public static HttpSession newFakeHttpSession() {
    return (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(),
        new Class[] { HttpSession.class }, new FakeHttpSessionHandler());
  }

  static void expectRequest(HttpServletRequest req, final String requestURI, final String servletPath,
      String pathInfo, final String contextPath) {
    req.setAttribute(ManagedServletPipeline.GUICE_MANAGED, Boolean.TRUE);
    expectLastCall().anyTimes();
    req.removeAttribute(ManagedServletPipeline.GUICE_MANAGED);
    expectLastCall().anyTimes();
    expect(req.getAttribute(ManagedServletPipeline.GUICE_MANAGED)).andReturn(Boolean.TRUE).anyTimes();

    for (String attr : ManagedServletPipeline.SPECIAL_ATTRIBUTES) {
      expect(req.getAttribute(attr)).andReturn(null).anyTimes();
    }

    expect(req.getMethod()).andReturn("GET").anyTimes();
    expect(req.getPathInfo()).andReturn(pathInfo).anyTimes();
    expect(req.getRequestURI()).andReturn(requestURI).anyTimes();
    expect(req.getServletPath()).andReturn(servletPath).anyTimes();
    expect(req.getContextPath()).andReturn(contextPath).anyTimes();
  }

}
