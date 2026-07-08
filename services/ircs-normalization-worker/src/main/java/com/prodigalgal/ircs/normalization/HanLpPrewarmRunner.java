package com.prodigalgal.ircs.normalization;

import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.other.CharTable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
@Slf4j
public class HanLpPrewarmRunner implements ApplicationRunner {

    private final boolean enabled;

    public HanLpPrewarmRunner(
            @Value("${app.normalization.hanlp-prewarm.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("HanLP prewarm is disabled");
            return;
        }
        prewarm();
    }

    void prewarm() {
        long start = System.currentTimeMillis();
        try {
            HanLP.segment("HanLP预热：Hello World, 2025! 启动！");
            HanLP.convertToSimplifiedChinese("測試繁體轉簡體");
            CharTable.convert("ＡＢＣ　１２３");
            log.info("HanLP prewarm completed in {}ms", System.currentTimeMillis() - start);
        } catch (RuntimeException ex) {
            log.error("HanLP prewarm failed: {}", ex.getMessage(), ex);
        }
    }
}
