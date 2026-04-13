# kube-playground

Scala client for the Kubernetes API

Typed Kubernetes Client for creating, reading, updating, removing, listing and watching resources on a Kubernetes cluster
`minikube start`

```sh
kubectl proxy --port=8080
Starting to serve on 127.0.0.1:8080
```

`curl -o k8s-openapi-v3-minikube-proxy.json http://localhost:8080/openapi/v3`