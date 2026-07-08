package com.prodigalgal.ircs.common.id;

import java.util.UUID;

public interface IrcsUuidGenerator {

    IrcsUuidStrategy strategy();

    UUID nextId();
}
