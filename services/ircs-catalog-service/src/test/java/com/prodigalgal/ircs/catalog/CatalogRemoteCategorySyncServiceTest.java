package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogRemoteCategorySyncServiceTest {

    private final FakeRepository repository = new FakeRepository();
    private final FakeFetchClient fetchClient = new FakeFetchClient();
    private final CatalogRemoteCategorySyncService service = new CatalogRemoteCategorySyncService(
            repository,
            new ObjectMapper(),
            fetchClient,
            new CatalogCategoryMappingPolicy());

    @Test
    void probesRemoteClassArrayWithoutPersistingLegacyRawCategories() {
        UUID dataSourceId = UUID.randomUUID();
        repository.dataSource = new DataSourceRead(
                dataSourceId,
                "fake",
                "https://example.test/",
                "api.php/provide/vod/",
                "{\"ac\":\"list\",\"pg\":\"{page}\"}",
                "/api.php/provide/vod/",
                "{\"ac\":\"detail\",\"ids\":\"{ids}\"}",
                "{}",
                null,
                null);
        fetchClient.body = """
                {
                  "class": [
                    { "type_id": "1", "type_name": "电影" },
                    { "type_id": "2", "type_name": "国产剧" },
                    { "type_id": "", "type_name": "跳过" }
                  ]
                }
                """;

        CatalogRemoteCategorySyncService.CategorySyncResult result =
                service.syncDataSourceCategories(dataSourceId);

        assertThat(result.success()).isTrue();
        assertThat(result.url())
                .isEqualTo("https://example.test/api.php/provide/vod/?limit=20&pagesize=20&ac=list&pg=1");
        assertThat(result.checked()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isZero();
    }

    @Test
    void categoryPolicyMapsRemoteCategoriesIntoTwelveStableTopCategories() {
        CatalogCategoryMappingPolicy policy = new CatalogCategoryMappingPolicy();

        assertThat(policy.inferCategoryName("剧情片")).isEqualTo("电影");
        assertThat(policy.inferCategoryName("大陆综艺")).isEqualTo("综艺");
        assertThat(policy.inferCategoryName("体育赛事")).isEqualTo("体育赛事");
        assertThat(policy.inferCategoryName("网红头条")).isEqualTo("成人");
        assertThat(policy.inferCategoryName("其他片源")).isEqualTo("其他");
    }

    private static final class FakeRepository extends JdbcCatalogRepository {

        private DataSourceRead dataSource;
        private FakeRepository() {
            super(null, null);
        }

        @Override
        public java.util.Optional<DataSourceRead> findDataSource(UUID id) {
            return dataSource != null && dataSource.id().equals(id)
                    ? java.util.Optional.of(dataSource)
                    : java.util.Optional.empty();
        }

    }

    private static final class FakeFetchClient extends CatalogFetchSampleClient {

        private String body;

        private FakeFetchClient() {
            super(null);
        }

        @Override
        String get(String url) {
            return body;
        }

        @Override
        String get(String url, SourceNetworkOptions options) {
            return body;
        }
    }
}
