package com.prodigalgal.ircs.interaction;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessageVisibilityRequest(@JsonProperty("public") Boolean publicMessage) {
}
