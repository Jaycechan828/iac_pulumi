package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.core.Output;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.ec2.Subnet;
import com.pulumi.aws.ec2.SubnetArgs;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;


import com.pulumi.*;
import com.pulumi.aws.ec2.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZonesResult;

import java.util.List;
import java.util.Stack;


public class App {
    public static void main(String[] args) {

        Pulumi.run(App::infraSetup);
    }

    private static void infraSetup(Context ctx) {
        var config = ctx.config();
        String cidrBlock = config.require("cidrBlock");
        String publicSubnetBaseCIDR = config.require("publicSubnetBaseCIDR");
        String privateSubnetBaseCIDR = config.require("privateSubnetBaseCIDR");
        String publicRouteTableCIDR = config.require("publicRouteTableCIDR");

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

            for (int i = 0; i <zoneSize && i <3; i++) {
                String zone = zones.get(i);


                int publicThirdOctet = extractThirdOctet(publicSubnetBaseCIDR)+i;
                int privateThirdOctet = extractThirdOctet(privateSubnetBaseCIDR) + i; // 如果您需要基于循环索引i调整私有子网的第三个八位组

                String publicSubnetCIDR = "10.0." + publicThirdOctet + ".0/24";
                String privateSubnetCIDR = "10.0." + privateThirdOctet + ".0/24";


                // Public subnet
                Subnet publicSubnet = new Subnet("public-subnet-" + i, SubnetArgs.builder()
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



            }
            return Output.ofNullable(null);
            // since we are not returning any value from the apply method
        });

        // Export the VPC ID and other resources if needed
        ctx.export("vpcId", vpc.id());
        // Add other exports here if necessary
    }
    private static int extractThirdOctet(String cidr) {
        String[] parts = cidr.split("\\.");
        return Integer.parseInt(parts[2]);
    }
}