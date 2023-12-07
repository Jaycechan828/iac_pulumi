package myproject;

import com.pulumi.Pulumi;
import com.pulumi.asset.AssetArchive;
import com.pulumi.asset.FileArchive;
import com.pulumi.asset.FileAsset;
import com.pulumi.aws.alb.*;
import com.pulumi.aws.alb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.alb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.Policy;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.dynamodb.TableArgs;
import com.pulumi.aws.dynamodb.inputs.TableAttributeArgs;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.Instance;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.lambda.FunctionArgs;
import com.pulumi.aws.lambda.Permission;
import com.pulumi.aws.lambda.PermissionArgs;
import com.pulumi.aws.lambda.inputs.FunctionEnvironmentArgs;
import com.pulumi.aws.opsworks.RdsDbInstance;
import com.pulumi.aws.opsworks.RdsDbInstanceArgs;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.rds.inputs.ParameterGroupParameterArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.inputs.GetZoneArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.aws.route53.outputs.GetZoneResult;
import com.pulumi.aws.s3.BucketObjectArgs;
import com.pulumi.aws.sns.Topic;
import com.pulumi.aws.sns.TopicArgs;
import com.pulumi.aws.sns.TopicSubscription;
import com.pulumi.aws.sns.TopicSubscriptionArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.s3.Bucket;
import com.pulumi.core.Output;
import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.s3.Bucket;
import com.pulumi.aws.lambda.Function;
import com.pulumi.aws.iam.Role;
import com.pulumi.aws.dynamodb.Table;
import com.pulumi.aws.iam.RolePolicyAttachment;
import com.pulumi.gcp.serviceaccount.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.deployment.InvokeOptions;
import com.pulumi.gcp.storage.*;
import com.pulumi.resources.CustomResourceOptions;

import static com.pulumi.aws.AwsFunctions.getAvailabilityZones;
import static com.pulumi.codegen.internal.Serialization.*;

import com.pulumi.aws.route53.*;


public class App {
    private static List<String> availabilityZone = new ArrayList<>();
    private static List<String> privateSubnets = new ArrayList<>();
    private static List<String> publicSubnetIds = new ArrayList<>();
    private static SubnetGroup privateSubnetsGroup;
    private static String DBAddress;
    private static String DBUsername;
    private static String DBPassword;

    public static void main(String[] args) {

        Pulumi.run(App::infraSetup);
    }

    private static void infraSetup(Context ctx) {


        var config = ctx.config();
        String cidrBlock = config.require("cidrBlock");
        String publicSubnetBaseCIDR = config.require("publicSubnetBaseCIDR");
        String privateSubnetBaseCIDR = config.require("privateSubnetBaseCIDR");
        String publicRouteTableCIDR = config.require("publicRouteTableCIDR");
        String amiId = config.require("amiId");
        String account = config.require("account");
        String DbPassword = "Qq18284530122";
        String[] publicSubnetCidrs = {"10.0.2.0/24", "10.0.3.0/24", "10.0.4.0/24"};
        String[] privateSubnetCidrs = {"10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"};
        String certificateArn = config.require("certificateArn");


        var vpc = new Vpc("my-vpc", VpcArgs.builder()
                .cidrBlock(cidrBlock)
                .build());

        var igw = new InternetGateway("my-igw", InternetGatewayArgs.builder()
                .vpcId(vpc.id())
                .build());

        var publicRouteTable = new RouteTable("public-route-table", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());

        var igwRoute = new Route("igw-route", RouteArgs.builder()
                .routeTableId(publicRouteTable.id())
                .destinationCidrBlock(publicRouteTableCIDR)
                .gatewayId(igw.id())
                .build(), CustomResourceOptions.builder()
                .dependsOn(igw).build());

        var privateRouteTable = new RouteTable("private-route-table", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());

        final var hosted = Route53Functions.getZone(GetZoneArgs.builder()
                .name(account + ".jaycec.me")
                .privateZone(false)
                .build());

        Output<GetAvailabilityZonesResult>
                getAvailabilityZones = getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                .state("available").build());

        getAvailabilityZones.apply(az -> {

            availabilityZone = az.names();
            int zoneSize = availabilityZone.size();

            // If region is changed, here should make adjustment
//            if ( zoneSize < 3){

            var publicSubnet1 = new Subnet("public-subnet-" + 0, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(publicSubnetCidrs[0])
                    .availabilityZone(az.names().get(0))
                    .mapPublicIpOnLaunch(true)
                    .build(), CustomResourceOptions.builder()
                    .dependsOn(igwRoute).build());
            new RouteTableAssociation("public-rta" + 0, RouteTableAssociationArgs.builder()
                    .subnetId(publicSubnet1.id())
                    .routeTableId(publicRouteTable.id())
                    .build());

            var privateSubnet1 = new Subnet("private-subnet" + 0, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(privateSubnetCidrs[0])
                    .availabilityZone(az.names().get(0))
                    .build());

            new RouteTableAssociation("private-rta-" + 0, RouteTableAssociationArgs.builder()
                    .subnetId(privateSubnet1.id())
                    .routeTableId(privateRouteTable.id())
                    .build());
            privateSubnet1.id().apply(id -> {
                privateSubnets.add(id);
                return Output.ofNullable(null);
            });

            var publicSubnet2 = new Subnet("public-subnet-" + 1, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(publicSubnetCidrs[1])
                    .availabilityZone(az.names().get(1))
                    .mapPublicIpOnLaunch(true)
                    .build(), CustomResourceOptions.builder()
                    .dependsOn(igwRoute).build());
            new RouteTableAssociation("public-rta-" + 1, RouteTableAssociationArgs.builder()
                    .subnetId(publicSubnet2.id())
                    .routeTableId(publicRouteTable.id())
                    .build());

            var privateSubnet2 = new Subnet("private-subnet" + 1, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(privateSubnetCidrs[1])
                    .availabilityZone(az.names().get(1))
                    .build());
            new RouteTableAssociation("private-rta-" + 1, RouteTableAssociationArgs.builder()
                    .subnetId(privateSubnet2.id())
                    .routeTableId(privateRouteTable.id())
                    .build());
            privateSubnet2.id().apply(id -> {
                privateSubnets.add(id);
                return Output.ofNullable(null);
            });

            var publicSubnet3 = new Subnet("public-subnet-" + 2, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(publicSubnetCidrs[2])
                    .availabilityZone(az.names().get(2))
                    .mapPublicIpOnLaunch(true)
                    .build(), CustomResourceOptions.builder()
                    .dependsOn(igwRoute).build());
            new RouteTableAssociation("public-rta-" + 2, RouteTableAssociationArgs.builder()
                    .subnetId(publicSubnet3.id())
                    .routeTableId(publicRouteTable.id())
                    .build());

            var privateSubnet3 = new Subnet("private-subnet" + 2, SubnetArgs.builder()
                    .vpcId(vpc.id())
                    .cidrBlock(privateSubnetCidrs[2])
                    .availabilityZone(az.names().get(2))
                    .build());
            new RouteTableAssociation("private-rta-" + 2, RouteTableAssociationArgs.builder()
                    .subnetId(privateSubnet3.id())
                    .routeTableId(privateRouteTable.id())
                    .build());
            privateSubnet3.id().apply(id -> {
                privateSubnets.add(id);
                return Output.ofNullable(null);
            });

            publicSubnet1.id().apply(id ->{
                publicSubnetIds.add(id);

                return Output.ofNullable(null);
            });
            publicSubnet2.id().apply(id ->{
                publicSubnetIds.add(id);

                return Output.ofNullable(null);
            });
            publicSubnet3.id().apply(id ->{
                publicSubnetIds.add(id);

                return Output.ofNullable(null);
            });

//            }

            Output.all(privateSubnet1.id(), privateSubnet2.id(), privateSubnet3.id()).apply(ids -> {
                privateSubnetsGroup = new SubnetGroup("db-private-subnets-group", SubnetGroupArgs.builder()
                        .subnetIds(ids)
                        .build());

                // WebApp
                // SecurityGroup
                // Configuration

                var appSecurityGroup = new SecurityGroup("app-security-group", new SecurityGroupArgs.Builder()
                        .vpcId(vpc.id())
                        .ingress(Arrays.asList(
                                new SecurityGroupIngressArgs.Builder()
                                        .protocol("tcp")
                                        .fromPort(22)
                                        .toPort(22)
                                        .cidrBlocks(Arrays.asList("0.0.0.0/0"))
                                        .build()
//                        new SecurityGroupIngressArgs.Builder()
//                                .protocol("tcp")
//                                .fromPort(80)
//                                .toPort(80)
//                                .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                .build(),
//                        new SecurityGroupIngressArgs.Builder()
//                                .protocol("tcp")
//                                .fromPort(443)
//                                .toPort(443)
//                                .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                .build()
//                        new SecurityGroupIngressArgs.Builder()
//                                .protocol("tcp")
//                                .fromPort(8080)
//                                .toPort(8080)
//                                .securityGroups(loadBalancerSecurityGroup.id().applyValue(List::of))
//                                .build()

                        ))
                        .egress(
//                        new SecurityGroupEgressArgs.Builder()
//                                .protocol("tcp")
//                                .fromPort(80)
//                                .toPort(80)
//                                .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                .build(),
                                new SecurityGroupEgressArgs.Builder()
                                        .protocol("-1")
                                        .fromPort(0)
                                        .toPort(0)
                                        .cidrBlocks(Arrays.asList("0.0.0.0/0"))
                                        .build()
                        )
                        .build());

                //RDS SecurityGroup
                var DBSecurityGroup = new SecurityGroup("csye6225-security-group", new SecurityGroupArgs.Builder()
                        .vpcId(vpc.id())
                        .ingress(Arrays.asList(
                                new SecurityGroupIngressArgs.Builder()
                                        .protocol("tcp")
                                        .fromPort(3306)
                                        .toPort(3306)
                                        .securityGroups(appSecurityGroup.id().applyValue(List::of))
                                        .build()
                        ))
                        .build());

                var appTpDb = new SecurityGroupRule("app-todb-outbound",
                        new SecurityGroupRuleArgs.Builder()
                                .securityGroupId(appSecurityGroup.id())
                                .type("egress")
                                .protocol("tcp")
                                .fromPort(3306)
                                .toPort(3306)
                                .sourceSecurityGroupId(DBSecurityGroup.id())
                                .build());

                var dbParameterGroup = new ParameterGroup("csye6225-db-parameter-group", ParameterGroupArgs.builder()
                        .family("mariadb10.6")
                        .description("Custom parameter group for mariadb10.6")
                        .build()
                );


                var rdsDbInstance = new com.pulumi.aws.rds.Instance("csye6225-database", com.pulumi.aws.rds.InstanceArgs.builder()
                        .engine("MariaDB")
                        .engineVersion("10.6.10")
                        .identifier("csye6225")
                        .instanceClass("db.t2.micro")
                        .dbSubnetGroupName(privateSubnetsGroup.name())
                        .storageType("gp2")
                        .allocatedStorage(20)
                        .username("csye6225")
                        .password("Qq18284530122")
                        .multiAz(false)
                        .dbName("csye6225")
                        .publiclyAccessible(false)
                        .vpcSecurityGroupIds(DBSecurityGroup.id().applyValue(List::of))
                        .parameterGroupName(dbParameterGroup.name())
                        .skipFinalSnapshot(true)
                        .build());

                var cloudWatchRole = new Role("logRole", RoleArgs.builder()
                        .assumeRolePolicy(serializeJson(
                                jsonObject(
                                        jsonProperty("Version", "2012-10-17"),
                                        jsonProperty("Statement", jsonArray(
                                                jsonObject(
                                                        jsonProperty("Effect", "Allow"),
                                                        jsonProperty("Principal", jsonObject(
                                                                jsonProperty("Service", jsonArray(
                                                                        "ec2.amazonaws.com",
                                                                        "lambda.amazonaws.com"
                                                                ))
                                                        )),
                                                        jsonProperty("Action", "sts:AssumeRole")
                                                )
                                        ))
                                ))
                        )
                        .build());


                var logPolicy = new RolePolicy(
                        "logPolicy",
                        RolePolicyArgs.builder()
                                .role(cloudWatchRole.id())
                                .policy(serializeJson(
                                        jsonObject(
                                                jsonProperty("Version", "2012-10-17"),
                                                jsonProperty("Statement", jsonArray(
                                                        jsonObject(
                                                                jsonProperty("Effect", "Allow"),
                                                                jsonProperty("Action", jsonArray(
                                                                        "cloudwatch:PutMetricData",
                                                                        "ec2:DescribeVolumes",
                                                                        "ec2:DescribeTags",
                                                                        "logs:PutLogEvents",
                                                                        "logs:DescribeLogStreams",
                                                                        "logs:DescribeLogGroups",
                                                                        "logs:CreateLogStream",
                                                                        "logs:CreateLogGroup",
                                                                        "SNS:Subscribe",
                                                                        "SNS:SetTopicAttributes",
                                                                        "SNS:RemovePermission",
//                                                                        "SNS:Receive",
                                                                        "SNS:Publish",
                                                                        "SNS:ListSubscriptionsByTopic",
                                                                        "SNS:GetTopicAttributes",
                                                                        "SNS:DeleteTopic",
                                                                        "SNS:AddPermission",
                                                                        "SNS:ListTopics",
                                                                        "dynamodb:*",
                                                                        "ses:*"
                                                                )),
                                                                jsonProperty("Resource", "*")
                                                        ),
                                                        jsonObject(
                                                                jsonProperty("Effect", "Allow"),
                                                                jsonProperty("Action", jsonArray("ssm:GetParameter")),
                                                                jsonProperty("Resource", "arn:aws:ssm:*:*:parameter/AmazonCloudWatch-*")
                                                        )
                                                ))
                                        )
                                ))
                                .build());

                var cwProfile = new InstanceProfile("Instance_CloudWatch_Profile", new InstanceProfileArgs.Builder()
                        .role(cloudWatchRole.name())
                        .build());

                rdsDbInstance.address().apply(address -> {
                    DBAddress = address;
                    rdsDbInstance.password().apply(password -> {
                        DBPassword = password.orElseThrow();
                        rdsDbInstance.username().apply(username -> {
                            DBUsername = username;

                            String userData = String.join("\n",
                                    "#!/bin/bash",
                                    "sudo groupadd csye6225",
                                    "sudo useradd -s /bin/false -g csye6225 -d /opt/csye6225 -m csye6225",
                                    "echo -e spring.jpa.hibernate.ddl-auto=update >> /opt/csye6225/application.properties",
                                    "echo -e spring.datasource.url=jdbc:mariadb://" + DBAddress + ":3306/csye6225 >> /opt/csye6225/application.properties",
                                    "echo -e spring.datasource.username=" + DBUsername + " >> /opt/csye6225/application.properties",
                                    "echo -e spring.datasource.password=" + DbPassword + " >> /opt/csye6225/application.properties",
                                    "echo -e spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect >> /opt/csye6225/application.properties",
                                    "echo -e spring.jpa.show-sql: true >> /opt/csye6225/application.properties",
                                    "echo -e spring.main.allow-circular-references=true >> /opt/csye6225/application.properties",
                                    "echo -e spring.main.allow-bean-definition-overriding=true >> /opt/csye6225/application.properties",
                                    "echo -e spring.datasource.driver-class-name=org.mariadb.jdbc.Driver >> /opt/csye6225/application.properties",
                                    "echo -e csv.file.path=file:/opt/csye6225/users.csv >> /opt/csye6225/application.properties",
                                    "sudo mv /opt/webapp.jar /opt/csye6225/webapp.jar",
                                    "sudo mv /opt/users.csv /opt/csye6225/users.csv",
                                    "sudo chown csye6225:csye6225 /opt/csye6225/webapp.jar",
                                    "sudo chown csye6225:csye6225 /opt/csye6225/users.csv",
                                    "sudo chown csye6225:csye6225 /opt/csye6225/application.properties",

                                    "sudo touch /var/log/csye6225.log",
                                    "sudo chown csye6225:csye6225 /var/log/csye6225.log",
                                    "sudo chmod u+rw,g+rw /var/log/csye6225.log",

                                    "sudo systemctl enable /etc/systemd/system/csye6225.service",
                                    "sudo systemctl start csye6225.service",

                                    "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \\\n" +
                                            "    -a fetch-config \\\n" +
                                            "    -m ec2 \\\n" +
                                            "    -c file:/opt/cloudwatch-config.json \\\n" +
                                            "    -s"
                            );

                            String accountName = "demo-account-id";
                            String projectIDString = "mydemo-406707";

                            // gcp service account
                            var serviceAccount = new Account("serviceAccount", AccountArgs.builder()
                                    .displayName(accountName)
                                    .accountId(accountName)
                                    .project(projectIDString)
                                    .build());

                            // bind Storage Object User role to service account
                            var serviceAccountEmail = serviceAccount.email();

                            serviceAccountEmail.applyValue(

                                    email -> {

                                        // bind service account  role to service account
                                        var serviceAccountRole = new BucketIAMMember("serviceAccountRole", BucketIAMMemberArgs.builder()
                                                .bucket("csye6225-demo-buckets")
                                                .role("roles/storage.objectUser")
                                                .member("serviceAccount:" + email)
                                                .build());

                                        // create access key
                                        var serviceAccountKey = new Key("serviceAccountKey", KeyArgs.builder()
                                                .serviceAccountId(serviceAccount.name())
                                                .publicKeyType("TYPE_X509_PEM_FILE")
                                                .build());

                                        // get private key
                                        var serviceAccountKeySecret = serviceAccountKey.privateKey();
                                        serviceAccountKeySecret.applyValue(
                                                secret -> {

                                                    var topic = new Topic("csye6225", TopicArgs.builder()
                                                            .displayName("csye6225")
                                                            .build());

//                                                    // Create DynamoDB table
//                                                    var emailTable = new Table("emailTrackingTable", TableArgs.builder()
//                                                            .name("emailTrackingTable")
//                                                            .attributes(
//                                                                    TableAttributeArgs.builder()
//                                                                            .name("email")
//                                                                            .type("S")
//                                                                            .build(),
//                                                                    TableAttributeArgs.builder()
//                                                                            .name("timestamp")
//                                                                            .type("S")
//                                                                            .build()
//                                                            )
//                                                            .hashKey("email")
//                                                            .rangeKey("timestamp")
//                                                            .billingMode("PAY_PER_REQUEST")
//                                                            .build());

//                                                    // Create Amazon Simple Notification Service (Amazon SNS) topic creation
//                                                    var topic = new Topic("csye6225", TopicArgs.builder()
//                                                            .displayName("csye6225")
//                                                            .build());

                                                    // send topic info to userdata
                                                    topic.urn().applyValue(
                                                            urn -> {

                                                                // join urn to userdata
                                                                String userDataWithTopic = userData + "\n" + "export TOPIC_INFO=" + urn;

                                                                // create s3 bucket
                                                                var s3Bucket = new Bucket("myBucket");

                                                                // upload file to s3 bucket
                                                                var s3BucketObject = new com.pulumi.aws.s3.BucketObject("myJar", BucketObjectArgs.builder()
                                                                        .bucket(s3Bucket.id())
                                                                        //
                                                                        //
                                                                        .source(new FileAsset("src/main/resources/lambda5-1.0-SNAPSHOT.jar"))
                                                                        .build());

                                                                // create a s3 key
                                                                var s3Key = s3BucketObject.key();

                                                                // Create Lambda function to download file
                                                                var lambdaFunction = new Function("myLambdaFunction", FunctionArgs.builder()
                                                                        .runtime("java17")
                                                                        .role(cloudWatchRole.arn())
                                                                        .timeout(300)
                                                                        .handler("northeastern.yihangchen.SnsEventHandler::handleRequest")
                                                                        .s3Bucket(s3Bucket.id())
                                                                        .s3Key(s3Key)
                                                                        .environment(FunctionEnvironmentArgs.builder()
                                                                                .variables(Map.of("gcpCredentialsSecret", secret, "apiKey", "md-AFSFvOuddKzbEncCvDOg4g"))
                                                                                .build())
                                                                        .build());

                                                                // Create sns subscription
                                                                var subscription = new TopicSubscription("subscription", TopicSubscriptionArgs.builder()
                                                                        .protocol("lambda")
                                                                        .endpoint(lambdaFunction.arn())
                                                                        .topic(topic.arn())
                                                                        .build());

                                                                // sns trigger lambda function
                                                                var permission = new Permission("triggerLambda", PermissionArgs.builder()
                                                                        .action("lambda:InvokeFunction")
                                                                        .function(lambdaFunction.arn())
                                                                        .principal("sns.amazonaws.com")
                                                                        .sourceArn(topic.arn())
                                                                        .build());

                                                                var appLaunchTemplate = new LaunchTemplate("csye6225_launch", new LaunchTemplateArgs.Builder()
                                                                        .imageId(amiId)
                                                                        .instanceType("t2.micro")
                                                                        .keyName("test")
                                                                        .userData(Base64.getEncoder().encodeToString(userDataWithTopic.getBytes()))
                                                                        .iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder()
                                                                                .name(cwProfile.name())
                                                                                .build())
                                                                        .blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
                                                                                .deviceName("/dev/xvda")
                                                                                .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
                                                                                        .volumeSize(25)
                                                                                        .volumeType("gp2")
                                                                                        .deleteOnTermination("true")
                                                                                        .build()).build())
                                                                        .networkInterfaces(LaunchTemplateNetworkInterfaceArgs.builder()
                                                                                .securityGroups(appSecurityGroup.id().applyValue(List::of))
                                                                                .associatePublicIpAddress("true")
                                                                                .subnetId(publicSubnet1.id())
                                                                                .build())
                                                                        .tagSpecifications(LaunchTemplateTagSpecificationArgs.builder()
                                                                                .resourceType("instance")
                                                                                .tags(Map.of("Name", "test"))
                                                                                .build())
                                                                        .build());

                                                                var loadBalancerSecurityGroup = new SecurityGroup("load-balancer-sg", new SecurityGroupArgs.Builder()
                                                                        .vpcId(vpc.id())
                                                                        .ingress(Arrays.asList(
//                                                                                new SecurityGroupIngressArgs.Builder()
//                                                                                        .protocol("tcp")
//                                                                                        .fromPort(80)
//                                                                                        .toPort(80)
//                                                                                        .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                                                                        .build(),
                                                                                new SecurityGroupIngressArgs.Builder()
                                                                                        .protocol("HTTPS")
                                                                                        .fromPort(443)
                                                                                        .toPort(443)
                                                                                        .cidrBlocks(Arrays.asList("0.0.0.0/0"))
                                                                                        .build()
//                        new SecurityGroupIngressArgs.Builder()
//                                .protocol("http")
//                                .fromPort(8080)
//                                .toPort(8080)
//                                .securityGroups(appSecurityGroup.id().applyValue(List::of))
//                                .build()
                                                                        ))
                                                                        .egress(SecurityGroupEgressArgs.builder()
                                                                                .fromPort(0)
                                                                                .toPort(0)
                                                                                .protocol("-1")
                                                                                .cidrBlocks("0.0.0.0/0")
                                                                                .build())
                                                                        .tags(Map.of("name", "loadBalancerSecurityGroup"))
                                                                        .build());

                                                                SecurityGroupRule LBTpApp = new SecurityGroupRule("LB-toApp-inbound",
                                                                        new SecurityGroupRuleArgs.Builder()
                                                                                .securityGroupId(appSecurityGroup.id())
                                                                                .type("ingress")
                                                                                .protocol("tcp")
                                                                                .fromPort(8080)
                                                                                .toPort(8080)
                                                                                .sourceSecurityGroupId(loadBalancerSecurityGroup.id())
                                                                                .build()
                                                                        ,CustomResourceOptions.builder()
                                                                        .dependsOn(loadBalancerSecurityGroup)
                                                                        .build());


                                                                var loadBalancer = new LoadBalancer("appLoadBalancer", LoadBalancerArgs.builder()
                                                                        .subnets(publicSubnetIds)
                                                                        .securityGroups(loadBalancerSecurityGroup.id().applyValue(List::of))
                                                                        .loadBalancerType("application")
                                                                        .enableDeletionProtection(false)
                                                                        .internal(false)
                                                                        .build());

                                                                var targetGroup = new TargetGroup("targetGroup", TargetGroupArgs.builder()
                                                                        .targetType("instance")
                                                                        .port(8080)
                                                                        .protocol("HTTP")
                                                                        .vpcId(vpc.id())
                                                                        .healthCheck(TargetGroupHealthCheckArgs.builder()
                                                                                .path("/healthz")
                                                                                .port("8080")
                                                                                .protocol("HTTP")
                                                                                .build())
                                                                        .build());

                                                                var listener = new Listener("listener", ListenerArgs.builder()
                                                                        .loadBalancerArn(loadBalancer.arn())
                                                                        .port(443)
                                                                        .protocol("HTTPS")
                                                                        .sslPolicy("ELBSecurityPolicy-2016-08")
                                                                        .certificateArn(certificateArn)
                                                                        .defaultActions(ListenerDefaultActionArgs.builder()
                                                                                .type("forward")
                                                                                .targetGroupArn(targetGroup.arn())
                                                                                .build())
                                                                        .build(),
                                                                        CustomResourceOptions.builder()
                                                                                .dependsOn(targetGroup)
                                                                                .build());

                                                                var appAutoScalingGroup = new com.pulumi.aws.autoscaling.Group("csye6225_asg", GroupArgs.builder()
                                                                        .desiredCapacity(1)
                                                                        .maxSize(3)
                                                                        .minSize(1)
                                                                        .defaultCooldown(120)
                                                                        .launchTemplate(GroupLaunchTemplateArgs.builder()
                                                                                .id(appLaunchTemplate.id())
                                                                                .version("$Latest")
                                                                                .build())
                                                                        .vpcZoneIdentifiers(publicSubnetIds)
                                                                        .targetGroupArns(targetGroup.arn().applyValue(List::of))
                                                                        .build());

                                                                var scaleUpPolicy = new com.pulumi.aws.autoscaling.Policy("Scale-up-policy", PolicyArgs.builder()
                                                                        .autoscalingGroupName(appAutoScalingGroup.name())
                                                                        .adjustmentType("ChangeInCapacity")
                                                                        .scalingAdjustment(1)
                                                                        .cooldown(300)
                                                                        .metricAggregationType("Average")
//                                  .minAdjustmentMagnitude(1)
                                                                        .policyType("SimpleScaling")
                                                                        .build(),
                                                                        CustomResourceOptions.builder()
                                                                                .dependsOn(appAutoScalingGroup)
                                                                                .build());
                                                                appAutoScalingGroup.name().apply(name ->{
                                                                    var cpuUtilizationAlarmHigh = new MetricAlarm("cpuHigh", MetricAlarmArgs.builder()
                                                                            .comparisonOperator("GreaterThanThreshold")
                                                                            .evaluationPeriods(1) // Number of periods over which data is compared
                                                                            .metricName("CPUUtilization")
                                                                            .namespace("AWS/EC2")
                                                                            .period(300) // Period in seconds over which the specified statistic is applied
                                                                            .statistic("Average")
                                                                            .threshold(5.0) // Set threshold to 5%
                                                                            .dimensions(Map.of("AutoScalingGroupName", name))
                                                                            .alarmActions(scaleUpPolicy.arn().applyValue(List::of)) // Trigger scale up policy
                                                                            .build());
                                                                    return Output.ofNullable(null);
                                                                });

                                                                var scaleDownPolicy = new Policy("Scale-down-policy", PolicyArgs.builder()
                                                                        .autoscalingGroupName(appAutoScalingGroup.name())
                                                                        .adjustmentType("ChangeInCapacity")
                                                                        .scalingAdjustment(-1)
                                                                        .cooldown(300)
                                                                        .metricAggregationType("Average")
//                                  .minAdjustmentMagnitude(1)
                                                                        .policyType("SimpleScaling")
                                                                        .build());

                                                                appAutoScalingGroup.name().apply(name ->{
                                                                    var cpuUtilizationAlarmLow = new MetricAlarm("cpuLow", MetricAlarmArgs.builder()
                                                                            .comparisonOperator("LessThanThreshold")
                                                                            .evaluationPeriods(1) // Number of periods over which data is compared
                                                                            .metricName("CPUUtilization")
                                                                            .namespace("AWS/EC2")
                                                                            .period(300) // Period in seconds over which the specified statistic is applied
                                                                            .statistic("Average")
                                                                            .threshold(3.0) // Set threshold to 3%
                                                                            .dimensions(Map.of("AutoScalingGroupName", name))
                                                                            .alarmActions(scaleDownPolicy.arn().applyValue(List::of)) // Trigger scale down policy
                                                                            .build());
                                                                    return Output.ofNullable(null);
                                                                });

                                                                var record = new Record("webapp", RecordArgs.builder()
                                                                        .zoneId(hosted.applyValue(getZoneResult -> getZoneResult.id()))
                                                                        .name(account +".jaycec.me")
                                                                        .type("A")
                                                                        .aliases(RecordAliasArgs.builder()
                                                                                .name(loadBalancer.dnsName())
                                                                                .zoneId(loadBalancer.zoneId())
                                                                                .evaluateTargetHealth(true)
                                                                                .build())
                                                                        .build());


                                                                return Output.ofNullable(null);
                                                            });
                                                    return Output.ofNullable(null);
                                                });
                                        return Output.ofNullable(null);
                                    });

//                            var appLaunchTemplate = new LaunchTemplate("csye6225_launch", new LaunchTemplateArgs.Builder()
//                                    .imageId(amiId)
//                                    .instanceType("t2.micro")
//                                    .keyName("test")
//                                    .userData(Base64.getEncoder().encodeToString(userData.getBytes()))
//                                    .iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder()
//                                            .name(cwProfile.name())
//                                            .build())
//                                    .blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
//                                            .deviceName("/dev/xvda")
//                                            .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
//                                                    .volumeSize(25)
//                                                    .volumeType("gp2")
//                                                    .deleteOnTermination("true")
//                                                    .build()).build())
//                                    .networkInterfaces(LaunchTemplateNetworkInterfaceArgs.builder()
//                                            .securityGroups(appSecurityGroup.id().applyValue(List::of))
//                                            .associatePublicIpAddress("true")
//                                            .subnetId(publicSubnet1.id())
//                                            .build())
//                                    .tagSpecifications(LaunchTemplateTagSpecificationArgs.builder()
//                                            .resourceType("instance")
//                                            .tags(Map.of("Name", "test"))
//                                            .build())
//                                    .build());
//
//                            var loadBalancerSecurityGroup = new SecurityGroup("load-balancer-sg", new SecurityGroupArgs.Builder()
//                                    .vpcId(vpc.id())
//                                    .ingress(Arrays.asList(
//                                            new SecurityGroupIngressArgs.Builder()
//                                                    .protocol("tcp")
//                                                    .fromPort(80)
//                                                    .toPort(80)
//                                                    .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                                    .build(),
//                                            new SecurityGroupIngressArgs.Builder()
//                                                    .protocol("tcp")
//                                                    .fromPort(443)
//                                                    .toPort(443)
//                                                    .cidrBlocks(Arrays.asList("0.0.0.0/0"))
//                                                    .build()
////                        new SecurityGroupIngressArgs.Builder()
////                                .protocol("http")
////                                .fromPort(8080)
////                                .toPort(8080)
////                                .securityGroups(appSecurityGroup.id().applyValue(List::of))
////                                .build()
//                                    ))
//                                    .egress(SecurityGroupEgressArgs.builder()
//                                            .fromPort(0)
//                                            .toPort(0)
//                                            .protocol("-1")
//                                            .cidrBlocks("0.0.0.0/0")
//                                            .build())
//                                    .tags(Map.of("name", "loadBalancerSecurityGroup"))
//                                    .build());
//
//                            SecurityGroupRule LBTpApp = new SecurityGroupRule("LB-toApp-inbound",
//                                    new SecurityGroupRuleArgs.Builder()
//                                            .securityGroupId(appSecurityGroup.id())
//                                            .type("ingress")
//                                            .protocol("tcp")
//                                            .fromPort(8080)
//                                            .toPort(8080)
//                                            .sourceSecurityGroupId(loadBalancerSecurityGroup.id())
//                                            .build()
//                                    ,CustomResourceOptions.builder()
//                                    .dependsOn(loadBalancerSecurityGroup)
//                                    .build());
//
//
//                            var loadBalancer = new LoadBalancer("appLoadBalancer", LoadBalancerArgs.builder()
//                                    .subnets(publicSubnetIds)
//                                    .securityGroups(loadBalancerSecurityGroup.id().applyValue(List::of))
//                                    .loadBalancerType("application")
//                                    .enableDeletionProtection(false)
//                                    .internal(false)
//                                    .build());
//
//                            var targetGroup = new TargetGroup("targetGroup", TargetGroupArgs.builder()
//                                    .targetType("instance")
//                                    .port(8080)
//                                    .protocol("HTTP")
//                                    .vpcId(vpc.id())
//                                    .healthCheck(TargetGroupHealthCheckArgs.builder()
//                                            .path("/healthz")
//                                            .port("8080")
//                                            .protocol("HTTP")
//                                            .build())
//                                    .build());
//
//                            var listener = new Listener("listener", ListenerArgs.builder()
//                                    .loadBalancerArn(loadBalancer.arn())
//                                    .port(80)
//                                    .protocol("HTTP")
//                                    .defaultActions(ListenerDefaultActionArgs.builder()
//                                            .type("forward")
//                                            .targetGroupArn(targetGroup.arn())
//                                            .build())
//                                    .build(),
//                                    CustomResourceOptions.builder()
//                                            .dependsOn(targetGroup)
//                                            .build());
//
//                            var appAutoScalingGroup = new com.pulumi.aws.autoscaling.Group("csye6225_asg", GroupArgs.builder()
//                                    .desiredCapacity(1)
//                                    .maxSize(3)
//                                    .minSize(1)
//                                    .defaultCooldown(120)
//                                    .launchTemplate(GroupLaunchTemplateArgs.builder()
//                                            .id(appLaunchTemplate.id())
//                                            .version("$Latest")
//                                            .build())
//                                    .vpcZoneIdentifiers(publicSubnetIds)
//                                    .targetGroupArns(targetGroup.arn().applyValue(List::of))
//                                    .build());
//
//                            var scaleUpPolicy = new com.pulumi.aws.autoscaling.Policy("Scale-up-policy", PolicyArgs.builder()
//                                    .autoscalingGroupName(appAutoScalingGroup.name())
//                                    .adjustmentType("ChangeInCapacity")
//                                    .scalingAdjustment(1)
//                                    .cooldown(300)
//                                    .metricAggregationType("Average")
////                                  .minAdjustmentMagnitude(1)
//                                    .policyType("SimpleScaling")
//                                    .build(),
//                                    CustomResourceOptions.builder()
//                                            .dependsOn(appAutoScalingGroup)
//                                            .build());
//                            appAutoScalingGroup.name().apply(name ->{
//                                var cpuUtilizationAlarmHigh = new MetricAlarm("cpuHigh", MetricAlarmArgs.builder()
//                                        .comparisonOperator("GreaterThanThreshold")
//                                        .evaluationPeriods(1) // Number of periods over which data is compared
//                                        .metricName("CPUUtilization")
//                                        .namespace("AWS/EC2")
//                                        .period(300) // Period in seconds over which the specified statistic is applied
//                                        .statistic("Average")
//                                        .threshold(5.0) // Set threshold to 5%
//                                        .dimensions(Map.of("AutoScalingGroupName", name))
//                                        .alarmActions(scaleUpPolicy.arn().applyValue(List::of)) // Trigger scale up policy
//                                        .build());
//                                return Output.ofNullable(null);
//                            });
//
//                            var scaleDownPolicy = new Policy("Scale-down-policy", PolicyArgs.builder()
//                                    .autoscalingGroupName(appAutoScalingGroup.name())
//                                    .adjustmentType("ChangeInCapacity")
//                                    .scalingAdjustment(-1)
//                                    .cooldown(300)
//                                    .metricAggregationType("Average")
////                                  .minAdjustmentMagnitude(1)
//                                    .policyType("SimpleScaling")
//                                    .build());
//
//                            appAutoScalingGroup.name().apply(name ->{
//                                var cpuUtilizationAlarmLow = new MetricAlarm("cpuLow", MetricAlarmArgs.builder()
//                                        .comparisonOperator("LessThanThreshold")
//                                        .evaluationPeriods(1) // Number of periods over which data is compared
//                                        .metricName("CPUUtilization")
//                                        .namespace("AWS/EC2")
//                                        .period(300) // Period in seconds over which the specified statistic is applied
//                                        .statistic("Average")
//                                        .threshold(3.0) // Set threshold to 3%
//                                        .dimensions(Map.of("AutoScalingGroupName", name))
//                                        .alarmActions(scaleDownPolicy.arn().applyValue(List::of)) // Trigger scale down policy
//                                        .build());
//                                return Output.ofNullable(null);
//                            });
//
//                            var record = new Record("webapp", RecordArgs.builder()
//                                    .zoneId(hosted.applyValue(getZoneResult -> getZoneResult.id()))
//                                    .name(account +".jaycec.me")
//                                    .type("A")
//                                    .aliases(RecordAliasArgs.builder()
//                                            .name(loadBalancer.dnsName())
//                                            .zoneId(loadBalancer.zoneId())
//                                            .evaluateTargetHealth(true)
//                                            .build())
//                                    .build());


//                            String accountName = "demo-account-id";
//                            String projectIDString = "mydemo-406707";
//
//                            // gcp service account
//                            var serviceAccount = new Account("serviceAccount", AccountArgs.builder()
//                                    .displayName(accountName)
//                                    .accountId(accountName)
//                                    .project(projectIDString)
//                                    .build());
//
//                            // bind Storage Object User role to service account
//                            var serviceAccountEmail = serviceAccount.email();
//
//                            serviceAccountEmail.applyValue(
//
//                                    email -> {
//
//                                        // bind service account  role to service account
//                                        var serviceAccountRole = new BucketIAMMember("serviceAccountRole", BucketIAMMemberArgs.builder()
//                                                .bucket("csye6225-demo-buckets")
//                                                .role("roles/storage.objectUser")
//                                                .member("serviceAccount:" + email)
//                                                .build());
//
//                                        // create access key
//                                        var serviceAccountKey = new Key("serviceAccountKey", KeyArgs.builder()
//                                                .serviceAccountId(serviceAccount.name())
//                                                .publicKeyType("TYPE_X509_PEM_FILE")
//                                                .build());
//
//                                        // get private key
//                                        var serviceAccountKeySecret = serviceAccountKey.privateKey();
//                                        serviceAccountKeySecret.applyValue(
//                                                secret -> {
//
//                                                    var topic = new Topic("csye6225", TopicArgs.builder()
//                                                            .displayName("csye6225")
//                                                            .build());
//
////                                                    // Create DynamoDB table
////                                                    var emailTable = new Table("emailTrackingTable", TableArgs.builder()
////                                                            .name("emailTrackingTable")
////                                                            .attributes(
////                                                                    TableAttributeArgs.builder()
////                                                                            .name("email")
////                                                                            .type("S")
////                                                                            .build(),
////                                                                    TableAttributeArgs.builder()
////                                                                            .name("timestamp")
////                                                                            .type("S")
////                                                                            .build()
////                                                            )
////                                                            .hashKey("email")
////                                                            .rangeKey("timestamp")
////                                                            .billingMode("PAY_PER_REQUEST")
////                                                            .build());
//
////                                                    // Create Amazon Simple Notification Service (Amazon SNS) topic creation
////                                                    var topic = new Topic("csye6225", TopicArgs.builder()
////                                                            .displayName("csye6225")
////                                                            .build());
//
//                                                    // send topic info to userdata
//                                                    topic.urn().applyValue(
//                                                            urn -> {
//
//                                                                // join urn to userdata
//                                                                String userDataWithTopic = userData + "\n" + "export TOPIC_INFO=" + urn;
//
//                                                                // create s3 bucket
//                                                                var s3Bucket = new Bucket("myBucket");
//
//                                                                // upload file to s3 bucket
//                                                                var s3BucketObject = new com.pulumi.aws.s3.BucketObject("myJar", BucketObjectArgs.builder()
//                                                                        .bucket(s3Bucket.id())
//                                                                        //
//                                                                        //
//                                                                        .source(new FileAsset("src/main/resources/lambda5-1.0-SNAPSHOT.jar"))
//                                                                        .build());
//
//                                                                // create a s3 key
//                                                                var s3Key = s3BucketObject.key();
//
//                                                                // Create Lambda function to download file
//                                                                var lambdaFunction = new Function("myLambdaFunction", FunctionArgs.builder()
//                                                                        .runtime("java17")
//                                                                        .role(cloudWatchRole.arn())
//                                                                        .timeout(300)
//                                                                        .handler("northeastern.yihangchen.SnsEventHandler::handleRequest")
//                                                                        .s3Bucket(s3Bucket.id())
//                                                                        .s3Key(s3Key)
//                                                                        .environment(FunctionEnvironmentArgs.builder()
//                                                                                .variables(Map.of("gcpCredentialsSecret", secret, "apiKey", "md-AFSFvOuddKzbEncCvDOg4g"))
//                                                                                .build())
//                                                                        .build());
//
//                                                                // Create sns subscription
//                                                                var subscription = new TopicSubscription("subscription", TopicSubscriptionArgs.builder()
//                                                                        .protocol("lambda")
//                                                                        .endpoint(lambdaFunction.arn())
//                                                                        .topic(topic.arn())
//                                                                        .build());
//
//                                                                // sns trigger lambda function
//                                                                var permission = new Permission("triggerLambda", PermissionArgs.builder()
//                                                                        .action("lambda:InvokeFunction")
//                                                                        .function(lambdaFunction.arn())
//                                                                        .principal("sns.amazonaws.com")
//                                                                        .sourceArn(topic.arn())
//                                                                        .build());
//
//                                                                return Output.ofNullable(null);
//                                                            });
//                                                    return Output.ofNullable(null);
//                                                });
//                                        return Output.ofNullable(null);
//                                    });

                            //End of obtaining RDS's Username
                            return Output.ofNullable(null);
                        });

                        //End of obtaining RDS's Password
                        return Output.ofNullable(null);
                    });

                    //End of obtaining RDS's Address
                    return Output.ofNullable(null);
                });

                //End of getting three PublicSubnets' Ids
                return Output.ofNullable(null);
            });


            //End of getAvailabilityZones Function
            return Output.ofNullable(null);
        });


    }

    private static int extractThirdOctet(String cidr) {
        String[] parts = cidr.split("\\.");
        return Integer.parseInt(parts[2]);
    }


}
