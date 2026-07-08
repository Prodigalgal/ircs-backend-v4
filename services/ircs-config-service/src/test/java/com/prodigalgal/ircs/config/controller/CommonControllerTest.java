package com.prodigalgal.ircs.config.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommonControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new CommonController()).build();

    @Test
    void returnsSortedTimezoneArrayForV1Frontend() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/common/timezones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasItem("Asia/Shanghai")))
                .andExpect(jsonPath("$", hasItem("UTC")))
                .andExpect(jsonPath("$", hasItem("America/New_York")))
                .andExpect(jsonPath("$", hasItem("Europe/London")))
                .andExpect(jsonPath("$", hasItem("Asia/Tokyo")))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> timezones = objectMapper.readValue(responseBody, new TypeReference<>() {});

        assertEquals(timezones.stream().sorted().toList(), timezones);
    }
}
