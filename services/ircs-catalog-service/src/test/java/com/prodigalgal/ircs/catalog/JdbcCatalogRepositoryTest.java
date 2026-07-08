package com.prodigalgal.ircs.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class JdbcCatalogRepositoryTest {

    @Test
    void seedMethodsAreIdempotentWithoutLegacyRawAttributeTables() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:catalog-seed-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setDriverClassName("org.h2.Driver");
        JdbcTemplate realJdbc = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate realNamed = new NamedParameterJdbcTemplate(dataSource);
        createSeedSchema(realJdbc);
        JdbcCatalogRepository realRepository = new JdbcCatalogRepository(realJdbc, realNamed);

        JdbcCatalogRepository.SeedResult category = realRepository.seedStandardCategory("电影", "movie");
        JdbcCatalogRepository.SeedResult categoryAgain = realRepository.seedStandardCategory("电影", "edited-movie");
        JdbcCatalogRepository.SeedResult genre = realRepository.seedStandardGenre("剧情");
        JdbcCatalogRepository.SeedResult area = realRepository.seedStandardArea("CN", "中国", "Asia");
        JdbcCatalogRepository.SeedResult language = realRepository.seedStandardLanguage("zh", "普通话", "Mandarin", "普通话");

        assertThat(category.inserted()).isTrue();
        assertThat(categoryAgain.inserted()).isFalse();
        assertThat(realJdbc.queryForObject("select count(*) from standard_category where name = '电影'", Integer.class))
                .isEqualTo(1);
        assertThat(realJdbc.queryForObject("select slug from standard_category where name = '电影'", String.class))
                .isEqualTo("movie");
        assertThat(realJdbc.queryForObject("select code from standard_genre where name = '剧情'", String.class))
                .startsWith("genre-");
        assertThat(area.id()).isNotNull();
        assertThat(language.id()).isNotNull();
        JdbcCatalogRepository.SeedResult dataSourceSeed = realRepository.seedDataSource(
                "光速资源",
                "https://api.guangsuapi.com",
                "/api.php/provide/vod/josn",
                "{\"ac\":\"list\",\"pg\":\"{page}\"}",
                "{\"ac\":\"detail\",\"ids\":\"{ids}\"}",
                "{\"detail_mapping\":{\"title\":{\"path\":\"$.list[0].vod_name\"}}}");
        JdbcCatalogRepository.SeedResult dataSourceAgain = realRepository.seedDataSource(
                "光速资源",
                "https://changed.example",
                "/changed",
                "{\"ac\":\"changed\"}",
                "{\"ac\":\"changed\"}",
                "{\"detail_mapping\":{\"title\":{\"path\":\"changed\"}}}");

        assertThat(dataSourceSeed.inserted()).isTrue();
        assertThat(dataSourceAgain.inserted()).isFalse();
        assertThat(dataSourceAgain.updated()).isFalse();
        assertThat(realJdbc.queryForObject("select count(*) from data_sources where name = '光速资源'", Integer.class))
                .isEqualTo(1);
        assertThat(realJdbc.queryForObject("select base_url from data_sources where name = '光速资源'", String.class))
                .isEqualTo("https://api.guangsuapi.com");
        assertThat(realRepository.listDataSourceSeedMappings())
                .singleElement()
                .satisfies(row -> assertThat(row.fieldMapping()).contains("vod_name"));

    }

    private void createSeedSchema(JdbcTemplate jdbc) {
        jdbc.execute(
                """
                create table standard_category (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    name varchar(100) unique not null,
                    slug varchar(100) unique not null
                )
                """);
        jdbc.execute(
                """
                create table standard_genre (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    name varchar(100) unique not null,
                    code varchar(100) unique not null
                )
                """);
        jdbc.execute(
                """
                create table standard_areas (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    name varchar(100) unique not null,
                    code varchar(10) unique not null,
                    region varchar(50)
                )
                """);
        jdbc.execute(
                """
                create table standard_languages (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    name varchar(100) unique not null,
                    code varchar(20),
                    english_name varchar(100),
                    native_name varchar(100)
                )
                """);
        jdbc.execute(
                """
                create domain if not exists jsonb as varchar
                """);
        jdbc.execute(
                """
                create table data_sources (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    name varchar(255) unique not null,
                    base_url varchar(1024) not null,
                    list_path varchar(1024) not null,
                    list_params jsonb,
                    detail_path varchar(1024) not null,
                    detail_params jsonb,
                    field_mapping jsonb,
                    adult_restricted boolean default false not null
                )
                """);
    }
}
