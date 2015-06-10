/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.compress_artifacts;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.BuildListener;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nonnull;

import jenkins.util.VirtualFile;

import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.util.URIUtil;
import org.apache.tools.ant.types.selectors.SelectorUtils;

import de.schlichtherle.truezip.file.TArchiveDetector;
import de.schlichtherle.truezip.file.TFile;
import de.schlichtherle.truezip.file.TVFS;
import de.schlichtherle.truezip.fs.archive.zip.JarDriver;
import de.schlichtherle.truezip.socket.sl.IOPoolLocator;
import de.schlichtherle.truezip.zip.ZipEntry;
import de.schlichtherle.truezip.zip.ZipFile;

final class ZipStorage extends VirtualFile {

    // JarDriver is a ZipDriver that uses UTF-8 for entry names
    private static final TArchiveDetector DETECTOR = new TArchiveDetector("zip", new JarDriver(IOPoolLocator.SINGLETON));

    static VirtualFile root(File archive) {
        return new ZipStorage(archive, "");
    }

    // TODO support updating entries
    static void archive(File archive, FilePath workspace, Launcher launcher, BuildListener listener, Map<String,String> artifacts) throws IOException, InterruptedException {
        // if the artifact is a single zip, skip compressing it so it is browsable in the UI.
        if (artifacts.size() == 1) {
            String artifact = artifacts.entrySet().iterator().next().getKey();
            if (artifact.endsWith(".zip")) {
                workspace.child(artifact).copyTo(new FilePath(archive));
                return;
            }
        }

        // Use temporary file for writing, rename when done
        File tempArchive = new File(archive.getAbsolutePath() + ".writing.zip");

        TFile zip = new TFile(tempArchive, DETECTOR);
        zip.mkdir(); // Create new archive file
        for (Entry<String, String> afs: artifacts.entrySet()) {
            FilePath src = workspace.child(afs.getKey());
            TFile dst = new TFile(zip, afs.getValue(), TArchiveDetector.NULL);
            if (src.isDirectory()) {
                dst.mkdirs();
            } else {
                TFile.cp(src.read(), dst);
            }
        }
        TVFS.umount(zip);
        tempArchive.renameTo(archive);
    }

    static boolean delete(File archive) throws IOException, InterruptedException {
        return archive.delete();
    }

    private final File archive;
    private final String path;

    private ZipStorage(File archive, String path) {
        this.archive = archive;
        this.path = path;
    }
    
    @Override public String getName() {
        return path.replaceFirst("^(.+/)?([^/]+)/?$", "$2");
    }
    
    @Override public URI toURI() {
        try {
            return new URI(null, URIUtil.encodePath(path), null);
        } catch (URISyntaxException x) {
            throw new AssertionError(x);
        } catch (URIException x) {
            throw new AssertionError(x);
        }
    }

    @Override public VirtualFile getParent() {
        int length = path.length();
        if (length == 0) return null; // Root has no parent

        int last = path.lastIndexOf('/');
        if (last < 0) return root(archive); // Top level file

        if (last + 1 != length) {
            return new ZipStorage(archive, path.substring(0, last + 1));
        }

        // trailing '/' found
        last = path.lastIndexOf('/', last - 1);
        if (last == -1) return root(archive); // Top level dir

        return new ZipStorage(archive, path.substring(0, last + 1));
    }

    private boolean looksLikeDir() {
        return path.length() == 0 || path.endsWith("/");
    }
    
    @Override public boolean isDirectory() throws IOException {
        if (!looksLikeDir() || !archive.exists()) {
            return false;
        }
        ZipFile zf = new ZipFile(archive);
        try {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (p.startsWith(path)) {
                    return true;
                }
            }
            return false;
        } finally {
            zf.close();
        }
    }
    
    @Override public boolean isFile() throws IOException {
        if (looksLikeDir() || !archive.exists()) {
            return false;
        }
        ZipFile zf = new ZipFile(archive);
        try {
            return zf.getEntry(path) != null;
        } finally {
            zf.close();
        }
    }
    
    @Override public boolean exists() throws IOException {
        if (!archive.exists()) return false;

        ZipFile zf = new ZipFile(archive);
        try {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (looksLikeDir() ? p.startsWith(path) : p.equals(path)) {
                    return true;
                }
            }
            return false;
        } finally {
            zf.close();
        }
    }
    
    @Override public VirtualFile[] list() throws IOException {
        if (!looksLikeDir() || !archive.exists()) {
            return new VirtualFile[0];
        }
        ZipFile zf = new ZipFile(archive);
        try {
            Set<VirtualFile> files = new HashSet<VirtualFile>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String p = entry.getName();
                if (p.startsWith(path)) {
                    files.add(new ZipStorage(archive, path + p.substring(path.length()).replaceFirst("/.+", "/")));
                }
            }
            return files.toArray(new VirtualFile[files.size()]);
        } finally {
            zf.close();
        }
    }
    
    @Override public String[] list(String glob) throws IOException {
        if (!looksLikeDir() || !archive.exists()) {
            return new String[0];
        }

        // canonical implementation treats null glob the same as empty string
        if (glob==null) {
            glob="";
        }

        ZipFile zf = new ZipFile(archive);
        try {
            Set<String> files = new HashSet<String>();
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
            	ZipEntry entry = entries.nextElement();
            	if ((! entry.isDirectory()) && entry.getName().startsWith(path)) {
            		String name = entry.getName().substring(path.length());
            		if (SelectorUtils.match(glob, name)) {
            			files.add(name);
            		}
            	}
            }
            return files.toArray(new String[files.size()]);
        } finally {
            zf.close();
        }
    }
    
    @Override public VirtualFile child(String name) {
        // TODO this is ugly; would be better to not require / on path
        ZipStorage f = new ZipStorage(archive, path + name + '/');
        try {
            if (f.isDirectory()) {
                return f;
            }
        } catch (IOException x) {
        }
        return new ZipStorage(archive, path + name);
    }
    
    @Override public long length() throws IOException {
        if (!archive.exists()) return 0;

        ZipFile zf = new ZipFile(archive);
        try {
            ZipEntry entry = zf.getEntry(path);
            return entry != null ? entry.getSize() : 0;
        } finally {
            zf.close();
        }
    }
    
    @Override public long lastModified() throws IOException {
        if (!archive.exists()) return 0;

        ZipFile zf = new ZipFile(archive);
        try {
            ZipEntry entry = zf.getEntry(path);
            return entry != null ? entry.getTime() : 0;
        } finally {
            zf.close();
        }
    }
    
    @Override public boolean canRead() throws IOException {
        return true;
    }

    @Override public InputStream open() throws IOException {
        if (!archive.exists()) throw new FileNotFoundException(path + " (No such file or directory)");

        if (looksLikeDir()) {
            // That is what java.io.FileInputStream.open throws
            throw new FileNotFoundException(this + " (Is a directory)");
        }
        final ZipFile zf = new ZipFile(archive);
        ZipEntry entry = zf.getEntry(path);
        if (entry == null) {
            zf.close();
            throw new FileNotFoundException(path + " (No such file or directory)");
        }

        return new EntryInputStream(zf, entry);
    }

    private static final class EntryInputStream extends FilterInputStream {
        private final @Nonnull ZipFile archive;
        private EntryInputStream(ZipFile archive, ZipEntry entry) throws IOException {
            super(archive.getInputStream(entry));
            this.archive = archive;
        }

        @Override
        public void close() throws IOException {
            super.close();
            archive.close();
        }
    }
}
