package io.agt.core;

/** 准备完成后才绑定片段身份，避免把改写前内容的摘要记到实际写入值上。 */
record PreparedDataInput<E>(E value, Object target) {}
