## Helm
```sh
values.yaml + templates/ → (Go template engine) → rendered K8s manifests → kubectl apply → cluster
```
In Helm's context, `render` means taking the Go template files (with placeholders like `{{ .Values.replicaCount }}`) and substituting in the actual values to produce final, valid Kubernetes YAML manifests.

```yaml
replicas: {{ .Values.replicaCount }}
image: {{ .Values.image.repository }}:{{ .Values.image.tag }}
```
Values (values.yaml):
```yaml
replicaCount: 3
image:
  repository: nginx
  tag: "1.25"
```
Rendered manifest:
```yaml
replicas: 3
image: nginx:1.25
```
This rendered YAML is what actually gets sent to the Kubernetes API server. You can preview it without installing by running `helm template <chart>` 

Charts can depend on other charts. For example, your app's chart can pull in a PostgreSQL sub-chart automatically. Dependencies are declared in `Chart.yaml` and managed with `helm dependency update`.

## Values Override Priority
Values can come from multiple sources, with later ones winning:
- `values.yaml` (chart defaults)
- Parent chart's `values.yaml`
- `-f custom-values.yaml` (user file)
- `--set key=value` (command line)

The rendered template gets applied at two points:
- `helm install` – Rendered manifests are sent to the Kubernetes API server to create resources for the first time.
- `helm upgrade` – Rendered manifests are sent again to update existing resources with any changes (new values, new chart version, etc.).

