package com.google.inject.servlet;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;

import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

@SuppressWarnings("rawtypes")
public class ServletContextWrapper implements ServletContext {

  private ServletContext servletContext;

  public ServletContextWrapper(ServletContext servletContext) {
    this.servletContext = servletContext;
  }

  public ServletContext getServletContext() {
    return servletContext;
  }

  public String getContextPath() {
    return servletContext.getContextPath();
  }

  public ServletContext getContext(String uripath) {
    return servletContext.getContext(uripath);
  }

  public int getMajorVersion() {
    return servletContext.getMajorVersion();
  }

  public int getMinorVersion() {
    return servletContext.getMinorVersion();
  }

  public String getMimeType(String file) {
    return servletContext.getMimeType(file);
  }

  public Set getResourcePaths(String path) {
    return servletContext.getResourcePaths(path);
  }

  public URL getResource(String path) throws MalformedURLException {
    return servletContext.getResource(path);
  }

  public InputStream getResourceAsStream(String path) {
    return servletContext.getResourceAsStream(path);
  }

  public RequestDispatcher getRequestDispatcher(String path) {
    return servletContext.getRequestDispatcher(path);
  }

  public RequestDispatcher getNamedDispatcher(String name) {
    return servletContext.getNamedDispatcher(name);
  }

  @Deprecated
  public Servlet getServlet(String name) throws ServletException {
    return servletContext.getServlet(name);
  }

  @Deprecated
  public Enumeration getServlets() {
    return servletContext.getServlets();
  }

  @Deprecated
  public Enumeration getServletNames() {
    return servletContext.getServletNames();
  }

  public void log(String msg) {
    servletContext.log(msg);
  }

  @Deprecated
  public void log(Exception exception, String msg) {
    servletContext.log(exception, msg);
  }

  public void log(String message, Throwable throwable) {
    servletContext.log(message, throwable);
  }

  public String getRealPath(String path) {
    return servletContext.getRealPath(path);
  }

  public String getServerInfo() {
    return servletContext.getServerInfo();
  }

  public String getInitParameter(String name) {
    return servletContext.getInitParameter(name);
  }

  public Enumeration getInitParameterNames() {
    return servletContext.getInitParameterNames();
  }

  public Object getAttribute(String name) {
    return servletContext.getAttribute(name);
  }

  public Enumeration getAttributeNames() {
    return servletContext.getAttributeNames();
  }

  public void setAttribute(String name, Object object) {
    servletContext.setAttribute(name, object);
  }

  public void removeAttribute(String name) {
    servletContext.removeAttribute(name);
  }

  public String getServletContextName() {
    return servletContext.getServletContextName();
  }

}
