package com.chy.zhikexing.util;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.chy.zhikexing.config.AliyunOSSProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AliyunOSSOperator {

    private final AliyunOSSProperties aliyunOSSProperties;

    public OssUploadResult upload(byte[] content, String originalFilename, String contentType, String chatId) throws Exception {
        String objectName = buildObjectName(originalFilename, chatId);
        OSS ossClient = buildClient();
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(contentType);
            ossClient.putObject(
                    aliyunOSSProperties.getBucketName(),
                    objectName,
                    new ByteArrayInputStream(content),
                    metadata
            );
        } finally {
            ossClient.shutdown();
        }
        return new OssUploadResult(aliyunOSSProperties.getBucketName(), objectName, buildUrl(objectName));
    }

    public byte[] download(String objectName) throws Exception {
        OSS ossClient = buildClient();
        try {
            OSSObject ossObject = ossClient.getObject(aliyunOSSProperties.getBucketName(), objectName);
            try (var inputStream = ossObject.getObjectContent()) {
                return inputStream.readAllBytes();
            }
        } finally {
            ossClient.shutdown();
        }
    }

    private OSS buildClient() throws Exception {
        EnvironmentVariableCredentialsProvider credentialsProvider =
                CredentialsProviderFactory.newEnvironmentVariableCredentialsProvider();
        ClientBuilderConfiguration clientBuilderConfiguration = new ClientBuilderConfiguration();
        clientBuilderConfiguration.setSignatureVersion(SignVersion.V4);
        return OSSClientBuilder.create()
                .endpoint(aliyunOSSProperties.getEndpoint())
                .credentialsProvider(credentialsProvider)
                .clientConfiguration(clientBuilderConfiguration)
                .region(aliyunOSSProperties.getRegion())
                .build();
    }

    private String buildObjectName(String originalFilename, String chatId) {
        String dir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String suffix = StringUtils.hasText(extension) ? "." + extension : "";
        String filename = UUID.randomUUID().toString().replace("-", "") + suffix;
        return "pdf/" + sanitizePathSegment(chatId) + "/" + dir + "/" + filename;
    }

    private String sanitizePathSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String buildUrl(String objectName) {
        String endpoint = aliyunOSSProperties.getEndpoint();
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        int protocolIndex = normalizedEndpoint.indexOf("://");
        if (protocolIndex < 0) {
            return normalizedEndpoint + "/" + objectName;
        }
        String protocol = normalizedEndpoint.substring(0, protocolIndex);
        String host = normalizedEndpoint.substring(protocolIndex + 3);
        return protocol + "://" + aliyunOSSProperties.getBucketName() + "." + host + "/" + objectName;
    }
}
