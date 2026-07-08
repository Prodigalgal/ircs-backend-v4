package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
class DoubanTrendListProvider implements TrendListProvider {

    private static final String MOVIE_CHART_URL = "https://movie.douban.com/chart";
    private static final String TV_API_BASE = "https://movie.douban.com/j/search_subjects";
    private static final Pattern ID_PATTERN = Pattern.compile("/subject/(\\d+)/?");

    private final ScraperTrendConfigValues configValues;
    private final TrendProviderHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "Douban";
    }

    @Override
    public List<TrendItemPayload> fetchTrending() {
        if (!configValues.doubanEnabled()) {
            return List.of();
        }
        List<TrendItemPayload> items = new ArrayList<>();
        fetchMovieTrends(items);
        fetchTvTrends(items);
        return limit(items);
    }

    private void fetchMovieTrends(List<TrendItemPayload> items) {
        Document doc = Jsoup.parse(httpClient.get(MOVIE_CHART_URL));
        Elements tables = doc.select("div.indent table");
        for (Element table : tables) {
            Element linkEl = table.selectFirst("a.nbg");
            if (linkEl == null) {
                continue;
            }
            String href = linkEl.attr("href");
            Matcher matcher = ID_PATTERN.matcher(href);
            if (!matcher.find()) {
                continue;
            }
            String title = linkEl.attr("title");
            String poster = table.select("img").attr("src").replace("s_ratio_poster", "l_ratio_poster");
            String year = yearFromText(table.selectFirst("p.pl") == null ? "" : table.selectFirst("p.pl").text());
            items.add(new TrendItemPayload(
                    title,
                    null,
                    null,
                    year,
                    null,
                    decimal(table.selectFirst("span.rating_nums") == null ? null : table.selectFirst("span.rating_nums").text()),
                    poster,
                    null,
                    matcher.group(1),
                    null,
                    "movie"));
        }
    }

    private void fetchTvTrends(List<TrendItemPayload> items) {
        String tag = URLEncoder.encode("热门", StandardCharsets.UTF_8);
        String url = TV_API_BASE + "?type=tv&tag=" + tag + "&sort=recommend&page_limit=50&page_start=0";
        try {
            JsonNode root = objectMapper.readTree(httpClient.get(url));
            JsonNode subjects = root.path("subjects");
            if (!subjects.isArray()) {
                return;
            }
            for (JsonNode node : subjects) {
                String id = node.path("id").asText();
                String title = node.path("title").asText();
                if (!StringUtils.hasText(id) || !StringUtils.hasText(title)) {
                    continue;
                }
                String cover = node.path("cover").asText();
                if (StringUtils.hasText(cover)) {
                    cover = cover.replace("s_ratio_poster", "l_ratio_poster");
                }
                items.add(new TrendItemPayload(
                        title,
                        null,
                        null,
                        null,
                        null,
                        decimal(node.path("rate").asText()),
                        cover,
                        null,
                        id,
                        null,
                        "tv"));
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Douban TV trend parse failed: " + ex.getMessage(), ex);
        }
    }

    private List<TrendItemPayload> limit(List<TrendItemPayload> items) {
        int max = configValues.maxProviderItems();
        return max <= 0 || items.size() <= max ? items : List.copyOf(items.subList(0, max));
    }

    private String yearFromText(String value) {
        if (value == null || !value.matches(".*\\d{4}.*")) {
            return null;
        }
        return value.replaceAll(".*?(\\d{4}).*", "$1");
    }

    private BigDecimal decimal(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
