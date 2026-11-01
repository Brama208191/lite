package dev.keel.model;

import dev.keel.core.ModelReply;

public interface ModelClient {

    String id();

    ModelReply next(ModelRequest request);
}
