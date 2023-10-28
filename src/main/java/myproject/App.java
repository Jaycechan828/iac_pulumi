package myproject;


import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.Instance;
import com.pulumi.aws.opsworks.RdsDbInstance;
import com.pulumi.aws.opsworks.RdsDbInstanceArgs;
import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.rds.inputs.ParameterGroupParameterArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.s3.Bucket;

import com.pulumi.*;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.SecurityGroup;

import java.util.*;


import com.pulumi.core.Output;
import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;

import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.pulumi.aws.outputs.GetAvailabilityZonesResult;
import com.pulumi.deployment.InvokeOptions;


public class App {

    private static Vpc vpc;
    private static InternetGateway igw;
    private static Subnet publicSubnet;
    private static Map<String, Subnet> publicSubnets = new HashMap<>();
    private static SubnetGroup privateSubnetsGroup;
    private static Subnet privateSubnet;
    private static Map<String, Subnet> privateSubnets = new HashMap<>();
    private static List<String> subnetsGroup = new ArrayList<>();
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

    private static void infraSetup(Context ctx){




    private static void infraSetup(Context ctx) {

        var config = ctx.config();
        String cidrBlock = config.require("cidrBlock");
        String publicSubnetBaseCIDR = config.require("publicSubnetBaseCIDR");
        String privateSubnetBaseCIDR = config.require("privateSubnetBaseCIDR");
        String publicRouteTableCIDR = config.require("publicRouteTableCIDR");
        String amiId = config.require("amiId");


        setVpc(cidrBlock);
        setIgw();
        setPublicRouteTable("public-route-table");
        setIgwRoute(publicRouteTableCIDR);
        setPrivateRouteTable("private-route-table");
        DbPassword = "Qq18284530122";
//        privateSubnetsGroup = new SubnetGroup("db-private-subnets-group");

        getAvailabilityZones().apply(az -> {
            List<String> zones = az.names();
            int zoneSize = zones.size();

        // Create a VPC
        Vpc vpc = new Vpc("my-vpc", VpcArgs.builder()
                .cidrBlock(cidrBlock)
                .build());

        // Create an Internet Gateway
        InternetGateway igw = new InternetGateway("my-igw", InternetGatewayArgs.builder()
                .vpcId(vpc.id())
                .build());

        // Create a public Route Table
        RouteTable publicRouteTable = new RouteTable("public-route-table", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());

        // Create a route in the public Route Table
        Route igwRoute = new Route("igw-route", RouteArgs.builder()
                .routeTableId(publicRouteTable.id())
                .destinationCidrBlock(publicRouteTableCIDR)
                .gatewayId(igw.id())
                .build());

        // Create a private Route Table
        RouteTable privateRouteTable = new RouteTable("private-route-table", RouteTableArgs.builder()
                .vpcId(vpc.id())
                .build());

        availabilityZones.apply(az -> {
            List<String> zones = az.names();
            int zoneSize = zones.size();



            for (int i = 0; i < zoneSize && i < 3; i++) {
                String zone = zones.get(i);


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
                    subnetsGroup.add(id);

                    return Output.ofNullable(null);
                });

            }

            try {
                Thread.sleep(18000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
//            CompletableFuture

            setPrivateSubnetsGroup("db-private-subnets-group", subnetsGroup);
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

        try {
            Thread.sleep(480000);
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
                "sudo systemctl enable /etc/systemd/system/csye6225.service",
                "sudo systemctl start csye6225.service"
        );

        setAppInstance("app-instance", amiId, userData);

        ctx.export("vpcId", vpc.id());
//        ctx.export("instance-id", appInstance.id());
//        ctx.export("dbinstance-id", rdsDbInstance.id());
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
                                .build(),
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
                                .build(),
                        new SecurityGroupIngressArgs.Builder()
                                .protocol("tcp")
                                .fromPort(8080)
                                .toPort(8080)
                                .cidrBlocks(Arrays.asList("0.0.0.0/0"))
                                .build()
                ))
                .build());
    }
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
                .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                        .volumeType("gp2")
                        .volumeSize(25)
                        .deleteOnTermination(true)
                        .build())
                .build());
    }

    


                private static int extractThirdOctet (String cidr){
                    String[] parts = cidr.split("\\.");
                    return Integer.parseInt(parts[2]);
                }
    }

