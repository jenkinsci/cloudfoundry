package org.cloudfoundry.samples;

import java.io.IOException;
import java.io.PrintWriter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class HelloServlet
  extends HttpServlet
{
  private static final long serialVersionUID = 1L;

  @Override
  public void init() throws ServletException {
    getServletContext().log("sleeping for 1s to help with timeout test");
    try {
      Thread.sleep(1000L);
      getServletContext().log("waking up now");
    } catch(InterruptedException e) {
      throw new ServletException(e);
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
    throws ServletException, IOException
  {
    response.setContentType("text/plain");
    response.setStatus(200);
    PrintWriter writer = response.getWriter();
    String ssh_client_info = System.getenv("SSH_CONNECTION");
    String ip_addr = System.getenv("CF_INSTANCE_IP");
    if (ip_addr.equals("0.0.0.0"))
    {
      int hubEnd = ssh_client_info.indexOf(" ");
      int portEnd = ssh_client_info.indexOf(" ", hubEnd + 1);
      int dockerIPAddressEnd = ssh_client_info.indexOf(" ", portEnd + 1);
      ip_addr = ssh_client_info.substring(portEnd + 1, dockerIPAddressEnd);
    }
    writer.println("Hello from " + ip_addr + ":" + System.getenv("PORT"));
    writer.close();
  }
}
