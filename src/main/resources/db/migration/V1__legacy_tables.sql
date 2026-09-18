CREATE TABLE IF NOT EXISTS `user_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_name` VARCHAR(64) NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `nick_name` VARCHAR(64) DEFAULT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_name` (`user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS `iiip_chat_record` (
  `id` VARCHAR(128) NOT NULL,
  `title` VARCHAR(255) DEFAULT NULL,
  `user_id` BIGINT NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_type_user_time` (`type`, `user_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `spring_ai_chat_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `conversation_id` VARCHAR(128) NOT NULL,
  `content` TEXT NOT NULL,
  `type` VARCHAR(32) NOT NULL,
  `timestamp` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_conversation_id` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `iiip_pdf_file` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `chat_id` VARCHAR(128) NOT NULL,
  `user_id` BIGINT NOT NULL,
  `original_filename` VARCHAR(255) NOT NULL,
  `oss_bucket` VARCHAR(128) NOT NULL,
  `oss_key` VARCHAR(512) NOT NULL,
  `file_size` BIGINT DEFAULT NULL,
  `content_type` VARCHAR(128) DEFAULT 'application/pdf',
  `vector_index_name` VARCHAR(128) DEFAULT NULL,
  `vector_status` TINYINT DEFAULT 0 COMMENT '0-未入库，1-已入库，2-入库失败',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_user` (`chat_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `course` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(128) NOT NULL,
  `edu` INT DEFAULT NULL COMMENT '0-无，1-初中，2-高中，3-大专，4-本科及以上',
  `type` VARCHAR(64) DEFAULT NULL,
  `price` BIGINT DEFAULT NULL,
  `duration` INT DEFAULT NULL COMMENT '学习时长，单位：天',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `school` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(128) NOT NULL,
  `city` VARCHAR(64) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `course_reservation` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `course` VARCHAR(128) NOT NULL,
  `student_name` VARCHAR(64) NOT NULL,
  `contact_info` VARCHAR(128) NOT NULL,
  `school` VARCHAR(128) NOT NULL,
  `remark` VARCHAR(500) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
