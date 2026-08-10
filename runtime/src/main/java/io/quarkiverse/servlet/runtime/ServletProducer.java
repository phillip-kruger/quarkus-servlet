package io.quarkiverse.servlet.runtime;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@Singleton
public class ServletProducer {

    @Produces
    @RequestScoped
    HttpServletRequest request() {
        return ServletRequestContext.get().getRequest();
    }

    @Produces
    @RequestScoped
    HttpServletResponse response() {
        return ServletRequestContext.get().getResponse();
    }

    @Produces
    @RequestScoped
    HttpSession session() {
        return ServletRequestContext.get().getRequest().getSession(true);
    }
}
