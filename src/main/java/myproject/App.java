package myproject;

import com.pulumi.*;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.ec2.SecurityGroup;

import java.util.*;

import com.pulumi.core.Output;
import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.aws.ec2.inputs.InstanceRootBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.ec2.Subnet;
import com.pulumi.aws.ec2.SubnetArgs;
import com.pulumi.Pulumi;

import java.util.Collections;
import java.util.List;


import java.util.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;


import com.pulumi.*;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.awt.AWTEventMulticaster.add;


public class App {
    private static Subnet publicSubnet;

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


        Output<GetAvailabilityZonesResult> availabilityZones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder()
                .state("available")
                .build());

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

                int privateThirdOctet = extractThirdOctet(privateSubnetBaseCIDR) + i; // 如果您需要基于循环索引i调整私有子网的第三个八位组

                String publicSubnetCIDR = "10.0." + publicThirdOctet + ".0/24";
                String privateSubnetCIDR = "10.0." + privateThirdOctet + ".0/24";


                // Public subnet

                publicSubnet = new Subnet("public-subnet-" + i, SubnetArgs.builder()

                        .vpcId(vpc.id())
                        .cidrBlock(publicSubnetCIDR)
                        .availabilityZone(zone)
                        .mapPublicIpOnLaunch(true)
                        .build());

                // Associate the public subnet with the public route table
                new RouteTableAssociation("public-rta-" + i, RouteTableAssociationArgs.builder()
                        .subnetId(publicSubnet.id())
                        .routeTableId(publicRouteTable.id())
                        .build());

                // Private subnet
                Subnet privateSubnet = new Subnet("private-subnet-" + i, SubnetArgs.builder()
                        .vpcId(vpc.id())
                        .cidrBlock(privateSubnetCIDR) // using i + 100 to avoid CIDR overlap
                        .availabilityZone(zone)
                        .build());

                // Associate the private subnet with the private route table
                new RouteTableAssociation("private-rta-" + i, RouteTableAssociationArgs.builder()
                        .subnetId(privateSubnet.id())
                        .routeTableId(privateRouteTable.id())
                        .build());



            SecurityGroup appSecurityGroup = new SecurityGroup("app-security-group", new SecurityGroupArgs.Builder()
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

//            Output<String> securityGroudId = appSecurityGroup.id();


//                        InstanceArgs instanceArgs = InstanceArgs.builder()
//                                .instanceType("t2.micro") // Set the instance type as per your requirements
//                                .ami(amiId)  // Replace with the actual AMI ID you want to use
//                                .subnetId(publicSubnet.id()) // Use the selected subnet ID as a string
//                                 // Customize tags as needed
//                                .keyName("test") // Specify the SSH key nam
//                                .vpcSecurityGroupIds(securityGroudId)
//                                .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
//                                        .volumeSize(25) // Replace with the desired volume size
//                                        .volumeType("gp2") // Replace with the desired volume type (e.g., gp2, io1, etc.)
//                                        .deleteOnTermination(true) // Set to true to delete the volume on instance termination
//                                        .build()
//                                )
//                                .build();


                                Instance appInstance = new Instance("app-instance", InstanceArgs.builder()
                                .ami(amiId)
                                .instanceType("t2.micro")  // or any other desired instance type
                                .vpcSecurityGroupIds(appSecurityGroup.id().applyValue(List::of))  // Adjusted line
                                .subnetId(publicSubnet.id())
                                .keyName("test")
                                .rootBlockDevice(InstanceRootBlockDeviceArgs.builder()
                                        .volumeType("gp2")
                                        .volumeSize(25)
                                        .deleteOnTermination(true)
                                        .build())
                                .build());



            ctx.export("vpcId", vpc.id());

            return Output.ofNullable(null);
        });
    }


                private static int extractThirdOctet (String cidr){
                    String[] parts = cidr.split("\\.");
                    return Integer.parseInt(parts[2]);
                }
    }

