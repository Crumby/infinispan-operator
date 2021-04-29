# Integration tests
Brief description on how to run integration tests.

## Prerequisities
* OpenShift 4
* Installed Operator
  * Operator has to watch different namespace then one it's installed in
* Either valid context in `kubeconfig` file or set properties below.

## Running the tests
* `mvn clean verify` to run all the tests
* `mvn clean verify -Dit.test=${testName}` to run particular test
* `mvn clean verify -Dit.test=org.infinispan.operator.*IT` to run functional tests that do not require cluster-admin access. User needs to have rights to manipulate with Infinispan CR objects however.
* `mvn clean verify -Dit.test=org.infinispan.operator.installation.*IT` to run Operator installation tests that require cluster-admin access

Tests retreive OpenShift context configuration from the current context in the `~/kube/config` file. You can override current context and fix the target environment using properties below.

### Properties
You can use some of the following properties to specify test environment as it fits to your needs:

```
// Specifies the namespace where the tests will be executed
xtf.openshift.namespace=infinispan-suite

// Specifies OpenShift to run the tests against
xtf.openshift.url=https://api.my.openshift.com:6443

// Specifies access token used to access the OpenShift
xtf.openshift.master.token=openshift-access-token

// Specify user credentials to access the OpenShift
xtf.openshift.master.username=admin
xtf.openshift.master.password=adminpassword

// Overrides default kubeconfig location used to retrieve the context
xtf.openshift.master.kubeconfig=/path/to/kubeconfig
```

### Params
Include following parameters as part of your test execution command to provide target OpenShift instance details, eg.:
* `-Dxtf.openshift.namespace=infinispan-suite`
* `-Dxtf.openshift.url=https://api.my.openshift.com:6443`
* `-Dxtf.openshift.master.token=openshift-access-token`

### Alternative
You can store your parameters in test.properties file in the the root of `test-integration` so you don't need to provide them repeatedly:

```
xtf.openshift.namespace=infinispan-suite
xtf.openshift.url=https://api.my.openshift.com:6443
xtf.openshift.master.token=openshift-access-token
```
