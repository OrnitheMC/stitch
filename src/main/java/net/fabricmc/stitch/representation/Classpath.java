package net.fabricmc.stitch.representation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;

public class Classpath {

    private static JarRootEntry jre;

    final JarRootEntry[] classpath;

    public Classpath(File jar, File... libs) throws IOException {
        this(jar, Arrays.asList(libs));
    }

    public Classpath(File jar, Collection<File> libs) throws IOException {
        this.classpath = new JarRootEntry[libs.size() + 1];

        int i = 0;
        this.classpath[i++] = new JarRootEntry(jar);
        for (File lib : libs) {
            this.classpath[i++] = new JarRootEntry(lib);
        }
    }

    public Classpath(JarRootEntry jar, JarRootEntry... libs) {
        this.classpath = new JarRootEntry[libs.length + 1];

        int i = 0;
        this.classpath[i++] = jar;
        for (JarRootEntry lib : libs) {
            this.classpath[i++] = lib;
        }
    }

    public JarRootEntry getJar() {
        return this.classpath[0];
    }

    private JarRootEntry getJre() {
        if (jre == null) {
            try {
                jre = new JarRootEntry(new File("."));
            } catch (Throwable t) {
                throw new RuntimeException("unable to create jre representation", t);
            }
        }

        return jre;
    }

    public JarClassEntry getClass(String name) {
        JarClassEntry c = getJar().getClass(name, null);
        if (c != null) {
            return c;
        }
        for (int i = 1; i < classpath.length; i++) {
            JarRootEntry jar = classpath[i];
            c = jar.getClass(name, null);
            if (c != null) {
                return c;
            }
        }
        return getJre().getClass(name, null);
    }

    public JarClassEntry findClass(String name) {
        try {
            JarClassEntry c = getJar().getClass(name, null);
            if (c != null) {
                return c;
            }
            for (int i = 1; i < classpath.length; i++) {
                JarRootEntry jar = classpath[i];
                c = jar.getClass(name, null);
                if (c != null) {
                    return c;
                }
                if (jar.classQueue.remove(name)) {
                    c = JarReader.readFromClasspath(jar, name);
                    if (c != null) {
                        return c;
                    }
                }
            }
            c = getJre().getClass(name, null);
            if (c != null) {
                return c;
            }

            return JarReader.readFromJre(jre, name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
