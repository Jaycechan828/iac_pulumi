package myproject;

import com.pulumi.Pulumi;
import com.pulumi.aws.alb.*;
import com.pulumi.aws.alb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.alb.inputs.LoadBalancerSubnetMappingArgs;
import com.pulumi.aws.alb.inputs.TargetGroupHealthCheckArgs;
import com.pulumi.aws.autoscaling.Group;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.Policy;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.cloudwatch.MetricAlarm;
import com.pulumi.aws.cloudwatch.MetricAlarmArgs;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.Instance;
import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.inputs.GetZoneArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.core.Output;
import com.pulumi.Context;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;

import java.util.*;

import com.pulumi.aws.outputs.GetAvailabilityZonesResult;

import static com.pulumi.codegen.internal.Serialization.*;
import com.pulumi.aws.route53.*;



public class App {

    private static Vpc vpc;
    private static InternetGateway igw;
    private static Subnet publicSubnet;
    private static Map<String, Subnet> publicSubnets = new HashMap<>();
    private static SubnetGroup privateSubnetsGroup;
    private static Subnet privateSubnet;
    private static Map<String, Subnet> privateSubnets = new HashMap<>();
    private static List<String> privtaeSubnetsGroup = new ArrayList<>();
    private static RouteTable publicRouteTable;
    private static RouteTable privateRouteTable;
    private static Route igwRoute;
    private static SecurityGroup appSecurityGroup;
    private static SecurityGroup DBSecurityGroup;
    private static ParameterGroup dbParameterGroup;
    private static com.pulumi.aws.rds.Instance rdsDbInstance;
    private static Instance appInstance;
    private static String DbAddress;
    private static String DbUsername;
    private static String DbPassword;
    private static String userData;
    private static Role cloudWatchRole;
    private static InstanceProfile cwProfile;
    private static SecurityGroup loadBalancerSecurityGroup;
    private static LaunchTemplate appLaunchTemplate;
    private static Group appAutoScalingGroup;
    private static List<String> AZ = new ArrayList<>();
    private static Policy scaleUpPolicy;
    private static Policy scaleDownPolicy;
    private static com.pulumi.aws.alb.TargetGroup targetGroup;
    private static com.pulumi.aws.alb.LoadBalancer loadBalancer;
    private static List<String> publicSubnetIds = new ArrayList<>();
    private static Listener listener;
    private static String subnet;


    public static void main(String[] args) {

        Pulumi.run(App::infraSetup);
    }
    private static void infraSetup(Context ctx){


        var config = ctx.config();
        String cidrBlock = config.require("cidrBlock");
        String publicSubnetBaseCIDR = config.require("publicSubnetBaseCIDR");
        String privateSubnetBaseCIDR = config.require("privateSubnetBaseCIDR");
        String publicRouteTableCIDR = config.require("publicRouteTableCIDR");
        String amiId = config.require("amiId");
        String account = config.require("account");

        setVpc(cidrBlock);
        setIgw();
        setPublicRouteTable("public-route-table");
        setIgwRoute(publicRouteTableCIDR);
        setPrivateRouteTable("private-route-table");
        DbPassword = "Qq18284530122";
//        privateSubnetsGroup = new SubnetGroup("db-private-subnets-group");


        final var hosted = Route53Functions.getZone(GetZoneArgs.builder()
                .name(account +".jaycec.me")
                .privateZone(false)
                .build());

        getAvailabilityZones().apply(az -> {
            AZ = az.names();
            int zoneSize = AZ.size();

            for (int i = 0; i < zoneSize && i < 3; i++) {
                String zone = AZ.get(i);


                int publicThirdOctet = extractThirdOctet(publicSubnetBaseCIDR) + i;
                int privateThirdOctet = extractThirdOctet(privateSubnetBaseCIDR) + i;

                String publicSubnetCIDR = "10.0." + publicThirdOctet + ".0/24";
                String privateSubnetCIDR = "10.0." + privateThirdOctet + ".0/24";

                setPublicSubnet("public-subnet-" + i, publicSubnetCIDR, zone);
                new RouteTableAssociation("public-rta-" + i, RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnet.id())
                        .routeTableId(publicRouteTable.id())
                        .build());

                setPrivateSubnet("private-subnet" + i, privateSubnetCIDR, zone);
                new RouteTableAssociation("private-rta-" + i, RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet.id())
                        .routeTableId(privateRouteTable.id())
                        .build());
                privateSubnet.id().apply(id ->{
                    privtaeSubnetsGroup.add(id);

                    return Output.ofNullable(null);
                });

                try {
                    Thread.sleep(20000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                publicSubnet.id().apply(id ->{
                    publicSubnetIds.add(id);

                    return Output.ofNullable(null);
                });


            }

            try {
                Thread.sleep(18000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
//            CompletableFuture

            setPrivateSubnetsGroup("db-private-subnets-group", privtaeSubnetsGroup);
            setAppSecurityGroup("app-security-group");
            setDBSecurityGroup("csye6225-security-group");
            SecurityGroupRule appTpDb = new SecurityGroupRule("app-todb-outbound",
                    new SecurityGroupRuleArgs.Builder()
                            .securityGroupId(appSecurityGroup.id())
                            .type("egress")
                            .protocol("tcp")
                            .fromPort(3306)
                            .toPort(3306)
                            .sourceSecurityGroupId(DBSecurityGroup.id())
                            .build());

            setDbParameterGroup("csye6225-db-parameter-group");


            setDbInstance("csye6225-database");
            setIAMRole("csye6225_CW");
            RolePolicyAttachment cwAttach = new RolePolicyAttachment("cloudWatchPolicyAttachment",
                    new RolePolicyAttachmentArgs.Builder()
                    .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
                            .role(cloudWatchRole.name())
                            .build());
            setInstanceProfile("CW_Profile");

            rdsDbInstance.address().apply(address ->{
                DbAddress = address;
                return Output.ofNullable(null);
            });

            rdsDbInstance.username().apply(username ->{
                DbUsername = username;
                return Output.ofNullable(null);
            });

            rdsDbInstance.password().applyValue(pw ->{
                DbPassword = pw.orElse("Qq18284530122");
                return Output.ofNullable(null);
            });

            return Output.ofNullable(null);
        });



        //RDS deploying waiting time = 500000
        try {
            Thread.sleep(530000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        userData = String.join("\n",
                "#!/bin/bash",
                "sudo groupadd csye6225",
                "sudo useradd -s /bin/false -g csye6225 -d /opt/csye6225 -m csye6225",
                "echo -e spring.jpa.hibernate.ddl-auto=update >> /opt/csye6225/application.properties" ,
                "echo -e spring.datasource.url=jdbc:mariadb://" + DbAddress + ":3306/csye6225 >> /opt/csye6225/application.properties",
                "echo -e spring.datasource.username=" + DbUsername + " >> /opt/csye6225/application.properties",
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

        setLaunchTemplate(amiId,userData, publicSubnetIds);
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        setLoadBalancerSecurityGroup();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        SecurityGroupRule LBTpApp = new SecurityGroupRule("LB-toApp-inbound",
                new SecurityGroupRuleArgs.Builder()
                        .securityGroupId(appSecurityGroup.id())
                        .type("ingress")
                        .protocol("tcp")
                        .fromPort(8080)
                        .toPort(8080)
                        .sourceSecurityGroupId(loadBalancerSecurityGroup.id())
                        .build());

        setLoadBalancer(publicSubnetIds);

        try {
            Thread.sleep(220000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        setTargetGroup();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        setListener();

        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        createASG(publicSubnetIds);
        try {
            Thread.sleep(170000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        setScaleUpPolicy();
        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        setScaleDownPolicy();

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

//        setAppInstance("app-instance", amiId, userData);

//        try {
//            Thread.sleep(63000);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//        Record record = new Record("webapp-record", new RecordArgs.Builder()
//                .type("A")
//                .name("")
//                .ttl(30)
//                .records(appInstance.publicIp().applyValue(List::of))
//                .zoneId(hosted.applyValue(getZoneResult -> getZoneResult.id()))
//                .build());

        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        Record record = new Record("webapp-record", new RecordArgs.Builder()
                .type("A")
                .name("")
                .ttl(30)
                .records(appInstance.publicIp().applyValue(List::of))
                .zoneId(hosted.applyValue(getZoneResult -> getZoneResult.id()))
                .build());

        ctx.export("vpcId", vpc.id());

    }

    //Get AvailabilityZones
    public static Output<GetAvailabilityZonesResult>
    getAvailabilityZones(){

        return AwsFunctions
                .getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                        .state("available")
                        .build());
    }

    private static void setVpc(String cidrBlock){
        vpc = new Vpc("my-vpc", VpcArgs.builder()
                .cidrBlock(cidrBlock)
                .build());
    }

    private static void setIgw(){
        igw = new InternetGateway("my-igw", InternetGatewayArgs.builder()
                .vpcId(vpc.id())
                .build());
    }

    private static void setPublicRouteTable(String name){
        publicRouteTable = new RouteTable(name, RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());
    }
    private static void setIgwRoute(String publicRouteTableCIDR){
        igwRoute = new Route("igw-route", RouteArgs.builder()
                .routeTableId(publicRouteTable.id())
                .destinationCidrBlock(publicRouteTableCIDR)
                .gatewayId(igw.id())
                .build());
    }

    private static void setPrivateRouteTable(String name){

        privateRouteTable = new RouteTable(name, RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());

    }

    private static void setPublicSubnet(String name, String publicSubnetCIDR, String zone){
        publicSubnet = new Subnet(name, SubnetArgs.builder()
                .vpcId(vpc.id())
                .cidrBlock(publicSubnetCIDR)
                .availabilityZone(zone)
                .mapPublicIpOnLaunch(true)
                .build());

        publicSubnets.put(String.valueOf(publicSubnets.size()), publicSubnet);
    }
    private static void setPrivateSubnet(String name, String privateSubnetCIDR, String zone){
        privateSubnet = new Subnet(name, SubnetArgs.builder()
                .vpcId(vpc.id())
                .cidrBlock(privateSubnetCIDR)
                .availabilityZone(zone)
                .build());


    }

    private static void setPrivateSubnetsGroup(String name, List<String> subnetsGroup){

        privateSubnetsGroup = new SubnetGroup(name, SubnetGroupArgs.builder()
                .subnetIds(subnetsGroup)
                .build());
    }
    private static void setAppSecurityGroup(String name){
        appSecurityGroup = new SecurityGroup(name, new SecurityGroupArgs.Builder()
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
    }

    private static void createASG(List<String> subnetIds){
        appAutoScalingGroup = new Group("csye6225_asg", GroupArgs.builder()
//                .availabilityZones(AZ)

                .desiredCapacity(1)
                .maxSize(3)
                .minSize(1)
                .defaultCooldown(120)
                .launchTemplate(GroupLaunchTemplateArgs.builder()
                        .id(appLaunchTemplate.id())
                        .version("$Latest")
                        .build())
                .vpcZoneIdentifiers(publicSubnetIds)
                //LoadBalancer
                .targetGroupArns(targetGroup.arn().applyValue(List::of))
//                .loadBalancers(loadBalancer.name().applyValue(List::of))
                .build());
    }
    private static void setScaleUpPolicy(){
        scaleUpPolicy = new Policy("Scale-up-policy", PolicyArgs.builder()
                .autoscalingGroupName(appAutoScalingGroup.name())
                .adjustmentType("ChangeInCapacity")
                .scalingAdjustment(1)
                .cooldown(300)
                .metricAggregationType("Average")
//                .minAdjustmentMagnitude(1)
                .policyType("SimpleScaling")
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


    }
    private static void setScaleDownPolicy(){
        scaleDownPolicy = new Policy("Scale-down-policy", PolicyArgs.builder()
                .autoscalingGroupName(appAutoScalingGroup.name())
                .adjustmentType("ChangeInCapacity")
                .scalingAdjustment(-1)
                .cooldown(300)
                .metricAggregationType("Average")
//                .minAdjustmentMagnitude(1)
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
    }



    private static void setLoadBalancerSecurityGroup() {
        loadBalancerSecurityGroup = new SecurityGroup("load-balancer-sg", new SecurityGroupArgs.Builder()
                .vpcId(vpc.id())
                .ingress(Arrays.asList(
                        new SecurityGroupIngressArgs.Builder()
                                .protocol("tcp")
                                .fromPort(80)
                                .toPort(80)
                                .cidrBlocks(Arrays.asList("0.0.0.0/0"))
                                .build(),
                        new SecurityGroupIngressArgs.Builder()
                                .protocol("tcp")
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
//                        .ipv6CidrBlocks("::/0")
                        .build())
                .tags(Map.of("name", "loadBalancerSecurityGroup"))
                .build());
    }
    private static void setLoadBalancer(List<String> subnetIds){
        loadBalancer = new LoadBalancer("appLoadBalancer", LoadBalancerArgs.builder()
                .subnets(publicSubnetIds)
                .securityGroups(loadBalancerSecurityGroup.id().applyValue(List::of))
                .loadBalancerType("application")
                .enableDeletionProtection(false)
                .internal(false)
//                .ipAddressType("ipv4")
//                .subnetMappings(LoadBalancerSubnetMappingArgs.builder()
//                        .subnetId(publicSubnets.get(String.valueOf(publicSubnets.size()-1)).id())
//                        .subnetId(publicSubnets.get(String.valueOf(publicSubnets.size()-2)).id())
//                        .subnetId(publicSubnets.get(String.valueOf(publicSubnets.size()-3)).id())
//                        .build())
                .build());
    }
    private static void setListener(){
        listener = new Listener("listener", ListenerArgs.builder()
                .loadBalancerArn(loadBalancer.arn())
                .port(80)
                .protocol("HTTP")
                .defaultActions(ListenerDefaultActionArgs.builder()
                        .type("forward")
                        .targetGroupArn(targetGroup.arn())
                        .build())
                .build());
    }
    private static void setTargetGroup(){
        targetGroup = new TargetGroup("targetGroup", TargetGroupArgs.builder()
                .targetType("instance")
                .port(8080)
                .protocol("HTTP")
//                .ipAddressType("ipv4")
                .vpcId(vpc.id())
                .healthCheck(TargetGroupHealthCheckArgs.builder()
                        .path("/healthz")
                        .port("8080")
//                        .enabled(true)
                        .protocol("HTTP")
//                        .timeout(20)
                        .build())
                .build());
    }

    private static void setLaunchTemplate(String amiId, String userData, List<String> subnetIds) {
        appLaunchTemplate = new LaunchTemplate("csye6225_launch", new LaunchTemplateArgs.Builder()
                .imageId(amiId)

//                .vpcSecurityGroupIds(appSecurityGroup.id().applyValue(List::of))
                .instanceType("t2.micro")
                .keyName("test")
                .userData(Base64.getEncoder().encodeToString(userData.getBytes()))
//                .vpcSecurityGroupIds(appSecurityGroup.id().applyValue(List::of))
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
                        .subnetId(publicSubnets.get(String.valueOf(publicSubnets.size()-1)).id())
                        .build())
                .tagSpecifications(LaunchTemplateTagSpecificationArgs.builder()
                        .resourceType("instance")
                        .tags(Map.of("Name", "test"))
                        .build())
                .build());
    }

//    private static void create53Record(){
//        var record = new Record("webapp", RecordArgs.builder()
//                .zoneId().build())
//    }
    private static void setDBSecurityGroup(String name){

        DBSecurityGroup = new SecurityGroup(name, new SecurityGroupArgs.Builder()
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
    }
    //    private static void associateDBandAppSG(){
//        appSecurityGroup.egress(Arrays.asList(
//                new SecurityGroupIngressArgs.Builder()
//                        .protocol("tcp")
//                        .fromPort(3306)
//                        .toPort(3306)
//                        .securityGroups(DBSecurityGroup.id().applyValue(List::of))
//                        .build()
//        ))
//    }
    private static void setDbParameterGroup(String name) {
        dbParameterGroup = new ParameterGroup(name, ParameterGroupArgs.builder()
                .family("mariadb10.6")
                .description("Custom parameter group for mariadb10.6")
                .build()
        );
    }
    private static void setDbInstance(String name) {
        rdsDbInstance = new com.pulumi.aws.rds.Instance(name, com.pulumi.aws.rds.InstanceArgs.builder()
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
    }

    private static void setAppInstance(String name, String amiId, String userData){
        appInstance = new Instance(name, InstanceArgs.builder()
                .ami(amiId)
                .instanceType("t2.micro")
                .vpcSecurityGroupIds(appSecurityGroup.id().applyValue(List::of))
                .subnetId(publicSubnets.get(String.valueOf(publicSubnets.size()-1)).id())
                .keyName("test")
                .userData(userData)
                .iamInstanceProfile(cwProfile.name())
                .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                        .volumeType("gp2")
                        .volumeSize(25)
                        .deleteOnTermination(true)
                        .build())

                .build());
    }
    private static void setIAMRole(String name){
        cloudWatchRole = new Role(name, RoleArgs.builder()
                .assumeRolePolicy(serializeJson(
                        jsonObject(
                                jsonProperty("Version", "2012-10-17"),
                                jsonProperty("Statement", jsonArray(jsonObject(
                                        jsonProperty("Action", "sts:AssumeRole"),
                                        jsonProperty("Effect", "Allow"),
                                        jsonProperty("Sid", ""),
                                        jsonProperty("Principal", jsonObject(
                                                jsonProperty("Service", "ec2.amazonaws.com")
                                        ))
                                )))
                        )))
                .build());
    }
    private static void setInstanceProfile (String name){
        cwProfile = new InstanceProfile(name, new InstanceProfileArgs.Builder()
                .role(cloudWatchRole.name())
                .build());
    }



    private static int extractThirdOctet(String cidr) {
        String[] parts = cidr.split("\\.");
        return Integer.parseInt(parts[2]);
    }

}
