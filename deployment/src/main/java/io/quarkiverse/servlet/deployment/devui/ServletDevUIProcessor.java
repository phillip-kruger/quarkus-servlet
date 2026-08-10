package io.quarkiverse.servlet.deployment.devui;

import io.quarkiverse.servlet.runtime.devui.ServletJsonRPCService;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devjsonrpc.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;

public class ServletDevUIProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    CardPageBuildItem pages() {
        CardPageBuildItem card = new CardPageBuildItem();

        card.addPage(Page.webComponentPageBuilder()
                .title("Servlets")
                .icon("font-awesome-solid:server")
                .componentLink("qwc-servlet-servlets.js"));

        card.addPage(Page.webComponentPageBuilder()
                .title("Filters")
                .icon("font-awesome-solid:filter")
                .componentLink("qwc-servlet-filters.js"));

        card.addPage(Page.webComponentPageBuilder()
                .title("Sessions")
                .icon("font-awesome-solid:users")
                .componentLink("qwc-servlet-sessions.js"));

        return card;
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    JsonRPCProvidersBuildItem rpcProvider() {
        return new JsonRPCProvidersBuildItem(ServletJsonRPCService.class);
    }
}
