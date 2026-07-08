package com.prodigalgal.ircs.ops.infrastructure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public final class JdbcPageSorts {

    private JdbcPageSorts() {
    }

    public static String orderBy(Pageable pageable, Map<String, String> columns, String defaultOrder) {
        if (pageable == null || pageable.getSort().isUnsorted()) {
            return defaultOrder;
        }

        List<String> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            String column = columns.get(order.getProperty());
            if (column != null) {
                orders.add(column + " " + (order.isAscending() ? "asc" : "desc"));
            }
        }
        if (orders.isEmpty()) {
            return defaultOrder;
        }
        return " order by " + String.join(", ", orders);
    }
}
