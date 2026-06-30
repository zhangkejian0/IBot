package com.xbot.xbot.web;

import android.content.Context;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Maps virtual-pet HTML and Vite chunk URLs to {@code assets/html/dist/}.
 *
 * <p>Vite emits {@code /assets/index-*.js} at the domain root, while Android
 * assets live under {@code html/dist/assets/}. Flutter avoids this with a local
 * HTTP server; native uses dual path handlers here.
 */
public final class VirtualPetAssetLoader {
    private static final String HTML_DIST_WEB_PREFIX = "/assets/html/dist/";
    private static final String HTML_DIST_ASSET_DIR = "html/dist/";
    private static final String VITE_ASSETS_DIR = "html/dist/assets/";

    private VirtualPetAssetLoader() {}

    public static WebViewAssetLoader create(Context context) {
        Context appContext = context.getApplicationContext();
        return new WebViewAssetLoader.Builder()
                .addPathHandler(HTML_DIST_WEB_PREFIX, new HtmlDistPathHandler(appContext))
                .addPathHandler("/assets/", new ViteRootAssetsHandler(appContext))
                .build();
    }

    public static String entryUrl() {
        return "https://appassets.androidplatform.net/assets/html/dist/index.html?style=ambient";
    }

    private static final class HtmlDistPathHandler implements WebViewAssetLoader.PathHandler {
        private final Context appContext;

        HtmlDistPathHandler(Context appContext) {
            this.appContext = appContext;
        }

        @Nullable
        @Override
        public WebResourceResponse handle(String path) {
            if (path == null || path.isEmpty()) {
                return openAsset(HTML_DIST_ASSET_DIR + "index.html");
            }
            String relative = path.startsWith("/") ? path.substring(1) : path;
            return openAsset(HTML_DIST_ASSET_DIR + relative);
        }

        @Nullable
        private WebResourceResponse openAsset(String assetPath) {
            try {
                InputStream in = appContext.getAssets().open(assetPath);
                return new WebResourceResponse(mimeForPath(assetPath), "utf-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static final class ViteRootAssetsHandler implements WebViewAssetLoader.PathHandler {
        private final Context appContext;

        ViteRootAssetsHandler(Context appContext) {
            this.appContext = appContext;
        }

        @Nullable
        @Override
        public WebResourceResponse handle(String path) {
            if (path == null || path.isEmpty()) {
                return null;
            }
            String relative = path.startsWith("/") ? path.substring(1) : path;
            if (relative.startsWith("html/")) {
                return null;
            }
            try {
                InputStream in = appContext.getAssets().open(VITE_ASSETS_DIR + relative);
                return new WebResourceResponse(mimeForPath(relative), "utf-8", in);
            } catch (IOException e) {
                return null;
            }
        }
    }

    private static String mimeForPath(String path) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(path);
        if (ext != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) {
                return mime;
            }
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "application/javascript";
        }
        if (path.endsWith(".css")) {
            return "text/css";
        }
        if (path.endsWith(".html")) {
            return "text/html";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }
}
