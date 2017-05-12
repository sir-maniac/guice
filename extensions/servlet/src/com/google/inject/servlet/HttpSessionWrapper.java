package com.google.inject.servlet;

import java.util.Enumeration;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;

/**
 * Simple wrapper that, by default, delegates all calls to underlying instance of HttpSession
 *
 */
@SuppressWarnings("deprecation")
public class HttpSessionWrapper implements HttpSession {

  private HttpSession session;

  public HttpSessionWrapper(HttpSession session) {
    this.session = session;
  }

  public long getCreationTime() {
    return session.getCreationTime();
  }

  public String getId() {
    return session.getId();
  }

  public long getLastAccessedTime() {
    return session.getLastAccessedTime();
  }

  public ServletContext getServletContext() {
    return session.getServletContext();
  }

  public void setMaxInactiveInterval(int interval) {
    session.setMaxInactiveInterval(interval);
  }

  public int getMaxInactiveInterval() {
    return session.getMaxInactiveInterval();
  }

  @Deprecated
  public HttpSessionContext getSessionContext() {
    return session.getSessionContext();
  }

  public Object getAttribute(String name) {
    return session.getAttribute(name);
  }

  @Deprecated
  public Object getValue(String name) {
    return session.getValue(name);
  }

  @SuppressWarnings("rawtypes")
  public Enumeration getAttributeNames() {
    return session.getAttributeNames();
  }

  @Deprecated
  public String[] getValueNames() {
    return session.getValueNames();
  }

  public void setAttribute(String name, Object value) {
    session.setAttribute(name, value);
  }

  @Deprecated
  public void putValue(String name, Object value) {
    session.putValue(name, value);
  }

  public void removeAttribute(String name) {
    session.removeAttribute(name);
  }

  @Deprecated
  public void removeValue(String name) {
    session.removeValue(name);
  }

  public void invalidate() {
    session.invalidate();
  }

  public boolean isNew() {
    return session.isNew();
  }


}
