package com.example;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

@WebListener
public class AppListener implements ServletContextListener {

    public static boolean initialized = false;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        initialized = true;
        sce.getServletContext().setAttribute("app.started", "true");
    }
}
