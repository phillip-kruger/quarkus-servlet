package io.quarkiverse.servlet.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.servlet")
public interface ServletBuildTimeConfig {

    /**
     * The servlet context path.
     */
    @WithDefault("/")
    String contextPath();
}
