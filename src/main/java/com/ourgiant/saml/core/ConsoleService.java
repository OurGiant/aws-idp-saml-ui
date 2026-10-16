package com.ourgiant.saml.core;

import java.util.Comparator;
import java.util.List;

/**
 * An AWS console service a profile's Open Console can land on (#239): a stable id stored in
 * samlsts ({@code consoleservice = ec2}), a display label, and the console path under
 * {@code console.aws.amazon.com}. A fixed list rather than a free-form URL, so nothing but a
 * known AWS console page is ever handed to the federation sign-in.
 */
public record ConsoleService(String id, String label, String path) {

    /** Common services, alphabetical by label. Paths are the standard console entry points. */
    public static final List<ConsoleService> ALL = List.of(
        new ConsoleService("apigateway", "API Gateway", "apigateway/home"),
        new ConsoleService("athena", "Athena", "athena/home"),
        new ConsoleService("backup", "AWS Backup", "backup/home"),
        new ConsoleService("bedrock", "Bedrock", "bedrock/home"),
        new ConsoleService("billing", "Billing and Cost Management", "billing/home"),
        new ConsoleService("acm", "Certificate Manager", "acm/home"),
        new ConsoleService("cloudformation", "CloudFormation", "cloudformation/home"),
        new ConsoleService("cloudfront", "CloudFront", "cloudfront/home"),
        new ConsoleService("cloudtrail", "CloudTrail", "cloudtrail/home"),
        new ConsoleService("cloudwatch", "CloudWatch", "cloudwatch/home"),
        new ConsoleService("codebuild", "CodeBuild", "codesuite/codebuild/home"),
        new ConsoleService("codepipeline", "CodePipeline", "codesuite/codepipeline/home"),
        new ConsoleService("cognito", "Cognito", "cognito/home"),
        new ConsoleService("config", "Config", "config/home"),
        new ConsoleService("costexplorer", "Cost Explorer", "cost-management/home"),
        new ConsoleService("dynamodb", "DynamoDB", "dynamodbv2/home"),
        new ConsoleService("ec2", "EC2", "ec2/home"),
        new ConsoleService("ecr", "Elastic Container Registry (ECR)", "ecr/home"),
        new ConsoleService("ecs", "Elastic Container Service (ECS)", "ecs/home"),
        new ConsoleService("efs", "Elastic File System (EFS)", "efs/home"),
        new ConsoleService("eks", "Elastic Kubernetes Service (EKS)", "eks/home"),
        new ConsoleService("elasticbeanstalk", "Elastic Beanstalk", "elasticbeanstalk/home"),
        new ConsoleService("elasticache", "ElastiCache", "elasticache/home"),
        new ConsoleService("events", "EventBridge", "events/home"),
        new ConsoleService("glue", "Glue", "glue/home"),
        new ConsoleService("guardduty", "GuardDuty", "guardduty/home"),
        new ConsoleService("iam", "IAM", "iam/home"),
        new ConsoleService("kms", "Key Management Service (KMS)", "kms/home"),
        new ConsoleService("kinesis", "Kinesis", "kinesis/home"),
        new ConsoleService("lambda", "Lambda", "lambda/home"),
        new ConsoleService("opensearch", "OpenSearch Service", "aos/home"),
        new ConsoleService("organizations", "Organizations", "organizations/home"),
        new ConsoleService("rds", "RDS", "rds/home"),
        new ConsoleService("redshift", "Redshift", "redshiftv2/home"),
        new ConsoleService("route53", "Route 53", "route53/home"),
        new ConsoleService("s3", "S3", "s3/home"),
        new ConsoleService("sagemaker", "SageMaker", "sagemaker/home"),
        new ConsoleService("secretsmanager", "Secrets Manager", "secretsmanager/home"),
        new ConsoleService("securityhub", "Security Hub", "securityhub/home"),
        new ConsoleService("servicequotas", "Service Quotas", "servicequotas/home"),
        new ConsoleService("sns", "Simple Notification Service (SNS)", "sns/home"),
        new ConsoleService("sqs", "Simple Queue Service (SQS)", "sqs/home"),
        new ConsoleService("stepfunctions", "Step Functions", "states/home"),
        new ConsoleService("support", "Support Center", "support/home"),
        new ConsoleService("ssm", "Systems Manager", "systems-manager/home"),
        new ConsoleService("trustedadvisor", "Trusted Advisor", "trustedadvisor/home"),
        new ConsoleService("vpc", "VPC", "vpc/home"),
        new ConsoleService("waf", "WAF & Shield", "wafv2/home")
    ).stream().sorted(Comparator.comparing(ConsoleService::label, String.CASE_INSENSITIVE_ORDER)).toList();

    /** The service with this id, or null (including for a blank or hand-edited unknown id). */
    public static ConsoleService find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ConsoleService service : ALL) {
            if (service.id.equals(id.trim())) {
                return service;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return label; // shown in the Edit Profile dropdown
    }
}
