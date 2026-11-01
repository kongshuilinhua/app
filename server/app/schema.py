"""Initial relational business schema, portable across SQLite/MySQL/PostgreSQL."""
from __future__ import annotations

from sqlalchemy import (JSON, BigInteger, Boolean, Column, DateTime, Float, ForeignKey, Index,
                        Integer, MetaData, String, Table, Text, UniqueConstraint)
from sqlalchemy.dialects.mysql import DATETIME, VARCHAR

SCHEMA_VERSION = "5"
ENTITY_ORDER = ("users", "cultivars", "classes", "config", "batches", "uploads", "samples", "images",
                "sessions", "exports", "export_items", "idempotency", "questions", "phone_identities", "sms_states")
PRIMARY_KEYS = {
    "phone_identities": ("phone_key",), "sms_states": ("record_key",),
    "batches": ("batch_id",), "questions": ("question_id",),
    "users": ("user_id",), "sessions": ("token_hash",), "cultivars": ("cultivar_id",),
    "classes": ("class_id",), "config": ("config_key",), "uploads": ("upload_id",),
    "samples": ("sample_id",), "images": ("image_id",), "exports": ("export_id",),
    "export_items": ("export_id", "image_id"), "idempotency": ("key_hash",),
}


def build_schema():
    metadata = MetaData()
    tables = {}
    timestamp = DateTime(timezone=True).with_variant(DATETIME(fsp=6), "mysql")

    def col(name, kind, *, nullable=False, **kwargs):
        return Column(name, kind, nullable=nullable, **kwargs)

    def ref(name, target, *, nullable=False, **kwargs):
        return Column(name, String(36), ForeignKey(target), nullable=nullable, **kwargs)

    def table(name, *columns):
        tables[name] = Table(name, metadata, *columns, mysql_engine="InnoDB", mysql_charset="utf8mb4")
        return tables[name]

    username = String(32).with_variant(VARCHAR(32, collation="utf8mb4_bin"), "mysql")
    table("users", col("user_id", String(36), primary_key=True),
          col("username", username, unique=True), col("display_name", String(64)),
          col("role", String(16)), col("is_active", Boolean), col("password_hash", String(255)),
          col("created_at", timestamp), col("last_login_at", timestamp, nullable=True))
    table("phone_identities", col("phone_key", String(64), primary_key=True),
          ref("user_id", "users.user_id", unique=True), col("masked_phone", String(16), nullable=True))
    table("sms_states", col("record_key", String(64), primary_key=True), col("data", JSON))
    table("cultivars", col("cultivar_id", String(64), primary_key=True), col("name", String(128)),
          col("is_active", Boolean), col("version", Integer))
    table("classes", col("class_id", String(128), primary_key=True), col("name", String(128)),
          col("parent_id", String(128), nullable=True), col("is_active", Boolean),
          col("is_provisional", Boolean), col("introduced_taxonomy_version", Integer),
          col("deprecated_taxonomy_version", Integer, nullable=True), col("version", Integer))
    table("config", col("config_key", String(32), primary_key=True),
          col("taxonomy_version", Integer), col("policy_version", Integer))
    table("batches", col("batch_id", String(64), primary_key=True), ref("owner_id", "users.user_id"),
          col("name", String(128)), col("place", String(256)), col("collector", String(128)),
          col("default_cultivar_id", String(64), nullable=True), col("note", Text, nullable=True),
          col("closed", Boolean), col("created_at", timestamp), col("updated_at", timestamp))
    table("questions", col("question_id", String(64), primary_key=True), ref("owner_id", "users.user_id"),
          col("text", Text), ref("sample_id", "samples.sample_id", nullable=True), col("status", String(16)),
          col("answer", Text, nullable=True), col("created_at", timestamp), col("answered_at", timestamp, nullable=True))
    table("uploads", col("upload_id", String(36), primary_key=True),
          col("sample_id", String(36), unique=True), ref("owner_id", "users.user_id"),
          col("state", String(16)), col("declared_at", timestamp),
          col("upload_completed_at", timestamp, nullable=True), col("content_hash", String(64)),
          col("declaration", JSON))
    table("samples", col("sample_id", String(36), primary_key=True),
          ref("owner_id", "users.user_id"), ref("upload_id", "uploads.upload_id", unique=True),
          col("batch_id", String(64), nullable=True), col("batch_snapshot", JSON, nullable=True),
          col("latitude", Float, nullable=True), col("longitude", Float, nullable=True), col("location_accuracy_m", Float, nullable=True),
          col("tree_id", String(64), nullable=True), col("original_cultivar_id", String(64)),
          col("original_cultivar_name", String(128)), col("original_part", String(16)),
          col("original_class_id", String(128)), col("original_class_name", String(128)),
          col("symptoms", Text, nullable=True), col("note", Text, nullable=True),
          col("taxonomy_version", Integer), col("upload_state", String(16)),
          col("upload_completed_at", timestamp, nullable=True), col("excluded", Boolean),
          col("review_status", String(16)), col("final_cultivar_id", String(64), nullable=True),
          col("final_cultivar_name", String(128), nullable=True), col("final_part", String(16), nullable=True),
          col("final_class_id", String(128), nullable=True), col("final_class_name", String(128), nullable=True),
          col("review_note", Text, nullable=True), col("review_version", Integer),
          col("reviewed_by_display_name", String(64), nullable=True), col("reviewed_at", timestamp, nullable=True),
          col("review_events", JSON))
    table("images", col("image_id", String(36), primary_key=True), ref("sample_id", "samples.sample_id"),
          col("sort_index", Integer), col("view_type", String(16)), col("captured_at", timestamp),
          col("device_timezone", String(64)), col("device_model", String(64), nullable=True),
          col("mime_type", String(32)), col("width", Integer), col("height", Integer),
          col("file_size", BigInteger), col("sha256", String(64)), col("receive_status", String(16)),
          col("file_key", String(512), nullable=True), col("quality_status", String(16)),
          col("quality_reason_code", String(32), nullable=True), col("quality_note", Text, nullable=True),
          col("quality_version", Integer), col("quality_reviewed_by_display_name", String(64), nullable=True),
          col("quality_reviewed_at", timestamp, nullable=True), col("clock_skew_suspected", Boolean),
          col("quality_events", JSON), UniqueConstraint("sample_id", "sort_index", name="uq_image_order"))
    table("sessions", col("token_hash", String(64), primary_key=True),
          col("kind", String(16)), col("session_id", String(36)), ref("user_id", "users.user_id"),
          col("expires_at", timestamp), col("revoked", Boolean))
    table("exports", col("export_id", String(36), primary_key=True), ref("owner_id", "users.user_id"),
          col("format", String(16)), col("filters", JSON), col("snapshot_at", timestamp),
          col("taxonomy_version", Integer), col("item_count", Integer), col("status", String(16)),
          col("zip_key", String(512), nullable=True), col("zip_sha256", String(64), nullable=True),
          col("zip_size", BigInteger, nullable=True), col("error_code", String(32), nullable=True),
          col("error_detail", Text, nullable=True), col("created_at", timestamp),
          col("started_at", timestamp, nullable=True), col("finished_at", timestamp, nullable=True))
    table("export_items", ref("export_id", "exports.export_id", primary_key=True),
          ref("image_id", "images.image_id", primary_key=True), ref("sample_id", "samples.sample_id"),
          col("sort_index", Integer), col("file_name", String(128)), col("file_key", String(512), nullable=True),
          col("class_id", String(128)), col("class_name", String(128), nullable=True), col("sha256", String(64)),
          col("collection_metadata", JSON, nullable=True))
    table("idempotency", col("key_hash", String(64), primary_key=True),
          col("request_hash", String(64)), ref("export_id", "exports.export_id"))

    Index("ix_samples_review", tables["samples"].c.upload_state,
          tables["samples"].c.review_status, tables["samples"].c.excluded)
    Index("ix_samples_completed", tables["samples"].c.upload_completed_at)
    Index("ix_sessions_user", tables["sessions"].c.user_id)
    Index("ix_sessions_family", tables["sessions"].c.session_id)
    Index("ix_exports_created", tables["exports"].c.created_at)
    Index("ix_exports_status", tables["exports"].c.status)
    lock = Table("meta_lock", metadata, col("id", Integer, primary_key=True), col("rev", Integer))
    version = Table("meta_schema", metadata, col("name", String(32), primary_key=True), col("value", String(64)))
    return metadata, tables, lock, version
