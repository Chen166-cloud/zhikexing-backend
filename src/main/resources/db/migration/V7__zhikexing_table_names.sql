-- Keep V1-V6 immutable so existing Flyway installations can upgrade safely.
-- These two legacy business tables are renamed without rewriting their data or keys.
RENAME TABLE iiip_chat_record TO zhikexing_chat_record,
             iiip_pdf_file TO zhikexing_pdf_file;
