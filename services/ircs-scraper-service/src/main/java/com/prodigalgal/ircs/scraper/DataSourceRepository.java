package com.prodigalgal.ircs.scraper;

import com.prodigalgal.ircs.scraper.ScraperDtos.DataSourceRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DataSourceRepository {

    private final JdbcTemplate jdbcTemplate;

    List<DataSourceRecord> findAll() {
        return jdbcTemplate.query("""
                select id,
                       name,
                       base_url,
                       list_path,
                       list_params::text,
                       detail_path,
                       detail_params::text,
                       field_mapping::text,
                       coalesce(transport_mode, 'AUTO') as transport_mode,
                       coalesce(http_protocol, 'AUTO') as http_protocol,
                       coalesce(ip_version_policy, 'AUTO') as ip_version_policy,
                       coalesce(dns_resolver_type, 'SYSTEM') as dns_resolver_type,
                       dns_resolver_endpoint,
                       connect_timeout_ms,
                       read_timeout_ms,
                       user_agent,
                       coalesce(adult_restricted, false) as adult_restricted
                from data_sources
                order by name asc
                """, this::map);
    }

    Optional<DataSourceRecord> findById(UUID id) {
        return jdbcTemplate.query("""
                select id,
                       name,
                       base_url,
                       list_path,
                       list_params::text,
                       detail_path,
                       detail_params::text,
                       field_mapping::text,
                       coalesce(transport_mode, 'AUTO') as transport_mode,
                       coalesce(http_protocol, 'AUTO') as http_protocol,
                       coalesce(ip_version_policy, 'AUTO') as ip_version_policy,
                       coalesce(dns_resolver_type, 'SYSTEM') as dns_resolver_type,
                       dns_resolver_endpoint,
                       connect_timeout_ms,
                       read_timeout_ms,
                       user_agent,
                       coalesce(adult_restricted, false) as adult_restricted
                from data_sources
                where id = ?
                """, this::map, id).stream().findFirst();
    }

    private DataSourceRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new DataSourceRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getString("list_path"),
                rs.getString("list_params"),
                rs.getString("detail_path"),
                rs.getString("detail_params"),
                rs.getString("field_mapping"),
                rs.getString("transport_mode"),
                rs.getString("http_protocol"),
                rs.getString("ip_version_policy"),
                rs.getString("dns_resolver_type"),
                rs.getString("dns_resolver_endpoint"),
                (Integer) rs.getObject("connect_timeout_ms"),
                (Integer) rs.getObject("read_timeout_ms"),
                rs.getString("user_agent"),
                rs.getBoolean("adult_restricted"));
    }
}
