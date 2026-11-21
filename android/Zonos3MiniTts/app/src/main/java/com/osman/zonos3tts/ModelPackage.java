package com.osman.zonos3tts;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ModelPackage {
    static final String MANIFEST = "zonos3-package.json";
    private static final long MAX_UNPACKED = 900L * 1024L * 1024L;
    private final Context context;

    static final class Installed {
        final File root;
        final File backbone;
        final File mmproj;
        final String modelId;
        final String variant;
        Installed(File root, File backbone, File mmproj, String modelId, String variant) {
            this.root=root; this.backbone=backbone; this.mmproj=mmproj; this.modelId=modelId; this.variant=variant;
        }
    }

    ModelPackage(Context context) { this.context=context.getApplicationContext(); }

    private File baseDir() { return new File(context.getNoBackupFilesDir(), "zonos3-model"); }
    private File currentDir() { return new File(baseDir(), "current"); }

    Installed current() throws Exception {
        File root=currentDir();
        if (!root.isDirectory()) throw new FileNotFoundException("Model ZIP henüz içe aktarılmadı");
        return validate(root);
    }

    Installed importZip(Uri uri) throws Exception {
        File base=baseDir();
        if (!base.exists() && !base.mkdirs()) throw new IOException("Model dizini oluşturulamadı");
        File next=new File(base,"next");
        deleteRecursively(next);
        if (!next.mkdirs()) throw new IOException("Geçici model dizini oluşturulamadı");

        long total=0;
        Set<String> seen=new HashSet<>();
        try (InputStream raw=context.getContentResolver().openInputStream(uri);
             ZipInputStream zin=new ZipInputStream(new BufferedInputStream(Objects.requireNonNull(raw)))) {
            ZipEntry e;
            byte[] buf=new byte[1024*1024];
            while ((e=zin.getNextEntry())!=null) {
                if (e.isDirectory()) continue;
                String name=e.getName();
                if (!isAllowedEntryName(name) || !seen.add(name)) throw new IOException("Geçersiz ZIP girdisi: "+name);
                File out=new File(next,name);
                long fileBytes=0;
                try (OutputStream os=new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n=zin.read(buf))>0) {
                        fileBytes+=n; total+=n;
                        if (total>MAX_UNPACKED) throw new IOException("Model paketi açılmış halde çok büyük");
                        os.write(buf,0,n);
                    }
                }
                if (fileBytes==0) throw new IOException("Boş dosya: "+name);
            }
        } catch (Throwable t) {
            deleteRecursively(next);
            throw t;
        }

        Installed checked=validate(next);
        File current=currentDir(), old=new File(base,"old");
        deleteRecursively(old);
        if (current.exists() && !current.renameTo(old)) {
            deleteRecursively(next);
            throw new IOException("Eski model güvenli biçimde taşınamadı");
        }
        if (!next.renameTo(current)) {
            if (old.exists()) old.renameTo(current);
            throw new IOException("Yeni model etkinleştirilemedi");
        }
        deleteRecursively(old);
        return validate(current);
    }

    static boolean isAllowedEntryName(String name) {
        if (name==null || name.length()<1 || name.length()>120) return false;
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return false;
        if (name.equals(MANIFEST)) return true;
        return name.matches("[A-Za-z0-9._-]+\\.gguf");
    }

    private Installed validate(File root) throws Exception {
        File manifestFile=new File(root,MANIFEST);
        if (!manifestFile.isFile()) throw new IOException("Paket manifesti yok");
        JSONObject manifest=new JSONObject(readText(manifestFile));
        if (manifest.optInt("schema",-1)!=1) throw new IOException("Desteklenmeyen paket şeması");
        String modelId=manifest.getString("model_id");
        String variant=manifest.optString("variant","");
        JSONObject files=manifest.getJSONObject("files");
        JSONObject b=files.getJSONObject("backbone");
        JSONObject m=files.getJSONObject("mmproj");
        File backbone=verifiedFile(root,b);
        File mmproj=verifiedFile(root,m);
        Set<String> allowed=new HashSet<>(Arrays.asList(MANIFEST,backbone.getName(),mmproj.getName()));
        File[] present=root.listFiles();
        if (present==null) throw new IOException("Model dizini okunamadı");
        for (File f:present) if (!allowed.contains(f.getName())) throw new IOException("Pakette beklenmeyen dosya: "+f.getName());
        return new Installed(root,backbone,mmproj,modelId,variant);
    }

    private static File verifiedFile(File root, JSONObject meta) throws Exception {
        String name=meta.getString("name");
        if (!isAllowedEntryName(name) || name.equals(MANIFEST)) throw new IOException("Geçersiz model dosya adı");
        File f=new File(root,name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        long expected=meta.getLong("size");
        if (f.length()!=expected) throw new IOException(name+" boyutu uyuşmuyor");
        String digest=sha256(f);
        if (!digest.equalsIgnoreCase(meta.getString("sha256"))) throw new IOException(name+" SHA-256 uyuşmuyor");
        return f;
    }

    private static String readText(File file) throws IOException {
        try (InputStream in=new FileInputStream(file); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n;
            while ((n=in.read(b))>0) out.write(b,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new BufferedInputStream(new FileInputStream(file))) {
            byte[] b=new byte[1024*1024]; int n;
            while ((n=in.read(b))>0) md.update(b,0,n);
        }
        StringBuilder s=new StringBuilder();
        for (byte x:md.digest()) s.append(String.format(Locale.ROOT,"%02x",x&255));
        return s.toString();
    }

    static void deleteRecursively(File f) {
        if (f==null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children=f.listFiles();
            if (children!=null) for (File c:children) deleteRecursively(c);
        }
        if (!f.delete() && f.exists()) throw new RuntimeException("Silinemedi: "+f);
    }
}
