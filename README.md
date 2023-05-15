Adapted from the @alzimmermsft test [here](https://github.com/alzimmermsft/DownloadInvestigation/tree/main).

This repo contains a [stress test](https://github.com/Azure/azure-sdk-tools/blob/main/tools/stress-cluster/chaos/README.md) for downloading blob data with partial fault injection.

The test will run in the stress cluster, first deploy a blob resource, then run the java test along with a sidecar running [http-fault-injector](https://github.com/Azure/azure-sdk-tools/tree/main/tools/http-fault-injector)

Stress test docs: https://github.com/Azure/azure-sdk-tools/blob/main/tools/stress-cluster/chaos/README.md


## Deploying the Stress Test

Requirements, see [installation](https://github.com/Azure/azure-sdk-tools/blob/main/tools/stress-cluster/chaos/README.md#installation)

From powershell:

```
<path to azure-sdk-for-java repository>/eng/common/scripts/stress-testing/deploy-stress-tests.ps1
```

To see test status:

```
kubectl get pods -n ($env:USERNAME ?? $env:USER)
```

Once the test pod is in state `Running`, to see test output:

```
# Add -f to tail logs
kubectl logs -n ($env:USERNAME ?? $env:USER) -l scenario=storagehttpstress -c main
```

To see the icm data generated by the test, and a dump of the logs, go to https://aka.ms/azsdk/stress/fileshare, click `browse`, select your username, and navigate to your test.
