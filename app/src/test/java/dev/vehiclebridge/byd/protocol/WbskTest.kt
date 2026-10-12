package dev.vehiclebridge.byd.protocol
import org.junit.Assert.*
import org.junit.Test
class WbskTest {
 @Test fun matchesNodeEnvelopesAcrossBlockBoundaries() {
  assertEquals("""Zr25mjRMFETHi1lVq4gFtUPKH+hwPEEQ8E2gAGn2fJLUN3HcMHUv8S7rEooD7ze3""", Wbsk.encrypt("""{}"""))
  assertEquals("""{}""", Wbsk.decrypt("""Zr25mjRMFETHi1lVq4gFtUPKH+hwPEEQ8E2gAGn2fJLUN3HcMHUv8S7rEooD7ze3"""))
  assertEquals("""A6bfaaVHxvIpql8cty2v9q3bU0CTTQ9iv7ewH02OE6bHSELxwBXnHYJ6vSADHk+k/zc9iewcewsO9iUDGXfziw==""", Wbsk.encrypt("""aaaaaaaaaaaaaaaa"""))
  assertEquals("""aaaaaaaaaaaaaaaa""", Wbsk.decrypt("""A6bfaaVHxvIpql8cty2v9q3bU0CTTQ9iv7ewH02OE6bHSELxwBXnHYJ6vSADHk+k/zc9iewcewsO9iUDGXfziw=="""))
  assertEquals("""+Ty82L3K1ekkzQRC4Fi32iwTzLY1s3z1+qeyUBgxTozIYMY7SqSehGSUq1dwmOkaD77y7dXP/xaXslTmNIaW8w==""", Wbsk.encrypt("""xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"""))
  assertEquals("""xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx""", Wbsk.decrypt("""+Ty82L3K1ekkzQRC4Fi32iwTzLY1s3z1+qeyUBgxTozIYMY7SqSehGSUq1dwmOkaD77y7dXP/xaXslTmNIaW8w=="""))
  assertEquals("""2OgZrznlvuiNfDCpCAJkR2p+itSDHg7vNVJb0dGkyH8p8dnSekrBDH8ln0Otc+QebUVMUddcx+x4MX5xzvgnGQ==""", Wbsk.encrypt("""车辆测试：仅合成数据"""))
  assertEquals("""车辆测试：仅合成数据""", Wbsk.decrypt("""2OgZrznlvuiNfDCpCAJkR2p+itSDHg7vNVJb0dGkyH8p8dnSekrBDH8ln0Otc+QebUVMUddcx+x4MX5xzvgnGQ=="""))
  assertEquals("""8hLB6+oP+v5fwD2ikGVFUCMeyr0+uRAzsf/RD1fQlZF9aPkkn7AO98aqZXfHnY8yyXiMvPp4/zVVa580z/BbfFoxHNjOFnjdJe+yLgczb0inXDGPJA2nkpf+8yNhlAHS""", Wbsk.encrypt("""{"code":"0","message":"synthetic"}"""))
  assertEquals("""{"code":"0","message":"synthetic"}""", Wbsk.decrypt("""8hLB6+oP+v5fwD2ikGVFUCMeyr0+uRAzsf/RD1fQlZF9aPkkn7AO98aqZXfHnY8yyXiMvPp4/zVVa580z/BbfFoxHNjOFnjdJe+yLgczb0inXDGPJA2nkpf+8yNhlAHS"""))
 }
 @Test fun rejectsMalformedEnvelope() {
  assertThrows(IllegalArgumentException::class.java) { Wbsk.decrypt("invalid-base64!") }
  assertThrows(IllegalArgumentException::class.java) { Wbsk.decrypt("AA==") }
 }
}
