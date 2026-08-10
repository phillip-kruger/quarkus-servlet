package io.quarkiverse.servlet.it;

import java.io.IOException;

import jakarta.servlet.ServletContext;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Reports the parts of the deployment that are only observable from inside the container. */
@WebServlet(urlPatterns = "/lifecycle")
public class LifecycleProbeServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        ServletContext context = getServletContext();
        resp.setContentType("text/plain");
        resp.getWriter().write(String.join("\n",
                "listenerRan=" + (context.getAttribute(DescriptorListener.RAN) != null),
                "sciRan=" + (context.getAttribute(TestContainerInitializer.RAN) != null),
                "sciHandled=" + context.getAttribute(TestContainerInitializer.HANDLED),
                // Reports what web.xml declares, not what the container implements.
                "effectiveVersion=" + context.getEffectiveMajorVersion()
                        + "." + context.getEffectiveMinorVersion(),
                "sessionTimeout=" + context.getSessionTimeout(),
                "programmaticRegistered="
                        + (context.getServletRegistration("programmatic") != null),
                "fragmentRegistered="
                        + (context.getServletRegistration("fragmentServlet") != null)));
    }
}
