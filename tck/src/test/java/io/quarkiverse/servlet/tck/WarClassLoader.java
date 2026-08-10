package io.quarkiverse.servlet.tck;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class WarClassLoader extends URLClassLoader {

    public WarClassLoader(Path classesDir, Path libDir, ClassLoader parent) throws IOException {
        super(buildUrls(classesDir, libDir), parent);
    }

    private static URL[] buildUrls(Path classesDir, Path libDir) throws IOException {
        List<URL> urls = new ArrayList<>();
        if (Files.exists(classesDir)) {
            urls.add(classesDir.toUri().toURL());
        }
        if (Files.exists(libDir)) {
            try (Stream<Path> libs = Files.list(libDir)) {
                libs.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().startsWith("jakarta.servlet-api"))
                        .forEach(p -> {
                            try {
                                urls.add(p.toUri().toURL());
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
        return urls.toArray(new URL[0]);
    }
}
