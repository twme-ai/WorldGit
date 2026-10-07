package org.worldgit.fabric;

import java.io.IOException;
import java.util.UUID;
import org.worldgit.core.model.DimensionId;

/** 還原會讓同一個 UUID 同時存在於兩個維度：寫入前拒絕，並指出已存在該實體的維度（不偷偷刪其他維度的實體）。 */
public final class DuplicateEntityException extends IOException {
    private final UUID uuid;
    private final DimensionId dimension;

    DuplicateEntityException(UUID uuid, DimensionId dimension) {
        super("目標實體 UUID " + uuid + " 已存在於維度 " + dimension + "；為避免 UUID 重複，已在寫入前拒絕");
        this.uuid = uuid;
        this.dimension = dimension;
    }

    public UUID uuid() { return uuid; }

    public DimensionId dimension() { return dimension; }
}
