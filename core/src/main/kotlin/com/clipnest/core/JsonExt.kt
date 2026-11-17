package com.clipnest.core

import com.google.gson.JsonObject

/** Gson JsonObject 小扩展：字段缺失/类型不符时一律回落默认值，不抛异常。 */

internal fun JsonObject.str(name: String): String =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asString }.getOrDefault("") else ""

internal fun JsonObject.strOrNull(name: String): String? =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asString }.getOrNull() else null

internal fun JsonObject.bool(name: String, def: Boolean): Boolean =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asBoolean }.getOrDefault(def) else def

internal fun JsonObject.int(name: String, def: Int): Int =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asInt }.getOrDefault(def) else def

internal fun JsonObject.longOrNull(name: String): Long? =
    if (has(name) && !get(name).isJsonNull) runCatching { get(name).asLong }.getOrNull() else null