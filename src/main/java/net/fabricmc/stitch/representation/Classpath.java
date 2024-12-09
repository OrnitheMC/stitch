package net.fabricmc.stitch.representation;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collection;

import net.ornithemc.nester.nest.Nests;

public class Classpath {

    private static JarRootEntry jre;

    final JarRootEntry[] classpath;
    final Nests nests;

    private boolean serializable;

    public Classpath(File jar, File... libs) throws IOException {
        this(jar, null, libs);
    }

    public Classpath(File jar, File nests, File... libs) throws IOException {
        this(jar, nests, Arrays.asList(libs));
    }

    public Classpath(File jar, Collection<File> libs) throws IOException {
        this(jar, null, libs);
    }

    public Classpath(File jar, File nests, Collection<File> libs) throws IOException {
        this.classpath = new JarRootEntry[libs.size() + 1];
        this.nests = nests == null ? Nests.empty() : Nests.of(nests.toPath());

        int i = 0;
        this.classpath[i++] = new JarRootEntry(jar);
        for (File lib : libs) {
            this.classpath[i++] = new JarRootEntry(lib);
        }
    }

    public Classpath(JarRootEntry jar, JarRootEntry... libs) {
        this(jar, Nests.empty(), libs);
    }

    public Classpath(JarRootEntry jar, Nests nests, JarRootEntry... libs) {
        this.classpath = new JarRootEntry[libs.length + 1];
        this.nests = nests;

        int i = 0;
        this.classpath[i++] = jar;
        for (JarRootEntry lib : libs) {
            this.classpath[i++] = lib;
        }
    }

    public void setSerializable(boolean serializable) {
        this.serializable = serializable;
    }

    public boolean isSerializable() {
        return serializable;
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

    public Nests getNests() {
        return nests;
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
