package android.net;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Uri {
    private final String uriString;
    private final URI parsed;

    private Uri(String uriString) {
        this.uriString = uriString;
        URI p = null;
        try {
            p = URI.create(uriString);
        } catch (Throwable ignored) {}
        this.parsed = p;
    }

    public static Uri parse(String uriString) {
        if (uriString == null) return null;
        return new Uri(uriString);
    }

    public String getScheme() {
        return parsed != null ? parsed.getScheme() : null;
    }

    public String getHost() {
        return parsed != null ? parsed.getHost() : null;
    }

    public String getPath() {
        return parsed != null ? parsed.getPath() : null;
    }

    public String getQuery() {
        return parsed != null ? parsed.getQuery() : null;
    }

    public String getQueryParameter(String key) {
        if (parsed == null || parsed.getQuery() == null) return null;
        String query = parsed.getQuery();
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length > 0 && pair[0].equals(key)) {
                return pair.length > 1 ? pair[1] : "";
            }
        }
        return null;
    }

    public Builder buildUpon() {
        return new Builder(this);
    }

    @Override
    public String toString() {
        return uriString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Uri uri = (Uri) o;
        return uriString.equals(uri.uriString);
    }

    @Override
    public int hashCode() {
        return uriString.hashCode();
    }

    public static class Builder {
        private String base;
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private String encodedQuery;

        public Builder() {
            this.base = "";
        }

        public Builder(Uri uri) {
            this.base = uri != null ? uri.uriString : "";
        }

        public Builder appendQueryParameter(String key, String value) {
            queryParams.put(key, value);
            return this;
        }

        public Builder encodedQuery(String query) {
            this.encodedQuery = query;
            return this;
        }

        public Uri build() {
            StringBuilder sb = new StringBuilder(base);
            if (encodedQuery != null) {
                if (!base.contains("?")) {
                    sb.append("?").append(encodedQuery);
                } else {
                    sb.append("&").append(encodedQuery);
                }
            }
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                char sep = sb.toString().contains("?") ? '&' : '?';
                sb.append(sep).append(entry.getKey()).append("=").append(entry.getValue());
            }
            return new Uri(sb.toString());
        }
    }
}
