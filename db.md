```sh
minikube start --memory=8192 --cpus=4 --disk-size=40g --vm-driver=virtualbox
helm repo add yugabytedb https://charts.yugabyte.com
helm repo update
kubectl create namespace yb-demo
helm install yb-demo yugabytedb/yugabyte --set resource.master.requests.cpu=0.5,resource.master.requests.memory=0.5Gi,resource.tserver.requests.cpu=0.5,resource.tserver.requests.memory=0.5Gi,replicas.master=1,replicas.tserver=1,Image.tag=2025.2.2.2-b11 --namespace yb-demo
```
