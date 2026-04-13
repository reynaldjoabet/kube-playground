// import kubescala.events.api.Events
// import kubescala.events.models.*
// import kubescala.autoscaling.api.Autoscaling
// import kubescala.apiserver.api.FlowcontrolApiserver
// import kubescala.autoscaling.v1.api.AutoscalingV1

// object KubernetesClient {

//   val pod = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("Pod"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.4.0.0/16",
//           serverAddress = "10.4.0.1"
//         )
//       )
//     )
//   )

//   val replicationController = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("ReplicationController"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.7.0.0/16",
//           serverAddress = "10.7.0.1"
//         )
//       )
//     )
//   )

//   val service = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("Service"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.8.0.0/16",
//           serverAddress = "10.8.0.1"
//         )
//       )
//     )
//   )

//   val namespace = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("Namespace"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.9.0.0/16",
//           serverAddress = "10.9.0.1"
//         )
//       )
//     )
//   )

//   val node = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("Node"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.10.0.0/16",
//           serverAddress = "10.10.0.1"
//         )
//       )
//     )
//   )

//   val podList = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("PodList"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.11.0.0/16",
//           serverAddress = "10.11.0.1"
//         )
//       )
//     )
//   )

//   val serviceList = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("ServiceList"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.12.0.0/16",
//           serverAddress = "10.12.0.1"
//         )
//       )
//     )
//   )

//   val nodeList = APIGroup(
//     name = "events.k8s.io",
//     versions = Seq(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       ),
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1beta1",
//         version = "v1beta1"
//       )
//     ),
//     apiVersion = Some("v1"),
//     kind = Some("NodeList"),
//     preferredVersion = Some(
//       GroupVersionForDiscovery(
//         groupVersion = "events.k8s.io/v1",
//         version = "v1"
//       )
//     ),
//     serverAddressByClientCIDRs = Some(
//       Seq(
//         ServerAddressByClientCIDR(
//           clientCIDR = "10.13.0.0/16",
//           serverAddress = "10.13.0.1"
//         )
//       )
//     )
//   )

//   val flowcontrolApiserverAPIGroup =
//     FlowcontrolApiserver()
//       .withBearerTokenAuth("your_token_here")
//       .getFlowcontrolApiserverAPIGroup

//   val autoscalingAPIGroup =
//     Autoscaling().withBearerTokenAuth("your_token_here").getAutoscalingAPIGroup

// }

object KubernetesClientTest {
  def main(args: Array[String]): Unit = {
    println("Testing Kubernetes Client...")
    // Here you would add code to test the Kubernetes client functionality
    // For example, you could create an instance of the client and call some methods
    // to verify that it interacts with the Kubernetes API correctly.
  }
}
