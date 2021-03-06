/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.privacy.methods.priv;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.hyperledger.besu.crypto.SECP256K1;
import org.hyperledger.besu.enclave.EnclaveClientException;
import org.hyperledger.besu.enclave.EnclaveServerException;
import org.hyperledger.besu.enclave.types.ReceiveResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequest;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.privacy.methods.EnclavePublicKeyProvider;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcSuccessResponse;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.Quantity;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.privacy.PrivateTransactionReceiptResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.TransactionLocation;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.PrivacyParameters;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.privacy.PrivacyController;
import org.hyperledger.besu.ethereum.privacy.PrivateTransaction;
import org.hyperledger.besu.ethereum.privacy.Restriction;
import org.hyperledger.besu.ethereum.privacy.storage.PrivateStateStorage;
import org.hyperledger.besu.ethereum.rlp.RLP;

import java.math.BigInteger;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;

import com.google.common.collect.Lists;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.impl.JWTUser;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PrivGetTransactionReceiptTest {

  @Rule public final TemporaryFolder temp = new TemporaryFolder();

  private static final String ENCLAVE_PUBLIC_KEY = "A1aVtMxLCUHmBVHXoZzzBgPbW/wj5axDpW9X8l91SGo=";
  private static final Bytes ENCLAVE_KEY = Bytes.wrap("EnclaveKey".getBytes(UTF_8));
  private static final Address SENDER =
      Address.fromHexString("0xfe3b557e8fb62b89f4916b721be55ceb828dbd73");

  private static final SECP256K1.KeyPair KEY_PAIR =
      SECP256K1.KeyPair.create(
          SECP256K1.PrivateKey.create(
              new BigInteger(
                  "8f2a55949038a9610f50fb23b5883af3b4ecb3c3bb792cbcefbd1542c692be63", 16)));

  private final PrivateTransaction privateTransaction =
      PrivateTransaction.builder()
          .nonce(0)
          .gasPrice(Wei.of(1000))
          .gasLimit(3000000)
          .to(null)
          .value(Wei.ZERO)
          .payload(
              Bytes.fromHexString(
                  "0x608060405234801561001057600080fd5b5060d08061001f60003960"
                      + "00f3fe60806040526004361060485763ffffffff7c01000000"
                      + "00000000000000000000000000000000000000000000000000"
                      + "60003504166360fe47b18114604d5780636d4ce63c14607557"
                      + "5b600080fd5b348015605857600080fd5b5060736004803603"
                      + "6020811015606d57600080fd5b50356099565b005b34801560"
                      + "8057600080fd5b506087609e565b6040805191825251908190"
                      + "0360200190f35b600055565b6000549056fea165627a7a7230"
                      + "5820cb1d0935d14b589300b12fcd0ab849a7e9019c81da24d6"
                      + "daa4f6b2f003d1b0180029"))
          .sender(SENDER)
          .chainId(BigInteger.valueOf(2018))
          .privateFrom(Bytes.wrap("A1aVtMxLCUHmBVHXoZzzBgPbW/wj5axDpW9X8l91SGo=".getBytes(UTF_8)))
          .privateFor(
              Lists.newArrayList(
                  Bytes.wrap("Ko2bVqD+nNlNYL5EE7y3IdOnviftjiizpjRt+HTuFBs=".getBytes(UTF_8))))
          .restriction(Restriction.RESTRICTED)
          .signAndBuild(KEY_PAIR);

  private final Transaction transaction =
      Transaction.builder()
          .nonce(0)
          .gasPrice(Wei.of(1000))
          .gasLimit(3000000)
          .to(Address.fromHexString("0x627306090abab3a6e1400e9345bc60c78a8bef57"))
          .value(Wei.ZERO)
          .payload(Bytes.wrap("EnclaveKey".getBytes(UTF_8)))
          .sender(SENDER)
          .chainId(BigInteger.valueOf(2018))
          .signAndBuild(KEY_PAIR);

  private final PrivateTransactionReceiptResult expectedResult =
      new PrivateTransactionReceiptResult(
          "0x0bac79b78b9866ef11c989ad21a7fcf15f7a18d7",
          "0xfe3b557e8fb62b89f4916b721be55ceb828dbd73",
          null,
          Collections.emptyList(),
          Bytes.EMPTY,
          null,
          0,
          0,
          Hash.fromHexString("0x65348ddfe0b282c26862b4610a8c45fd8486a93ae6e2b197836c826b4b671848"),
          Hash.fromHexString("0x43ef5094212ba4862d6b310a3d337c3478fdf942c5ed3f8e792ad93d6d96994d"),
          Bytes.fromHexString(
              "0x41316156744d784c4355486d425648586f5a7a7a42675062572f776a3561784470573958386c393153476f3d"),
          Collections.singletonList(
              Bytes.fromHexString(
                  "0x4b6f32625671442b6e4e6c4e594c35454537793349644f6e766966746a69697a706a52742b4854754642733d")),
          null,
          null,
          Quantity.create(Bytes.of(1).toUnsignedBigInteger()));

  private final User user =
      new JWTUser(new JsonObject().put("privacyPublicKey", ENCLAVE_PUBLIC_KEY), "");
  private final EnclavePublicKeyProvider enclavePublicKeyProvider = (user) -> ENCLAVE_PUBLIC_KEY;

  private final BlockchainQueries blockchainQueries = mock(BlockchainQueries.class);
  private final Blockchain blockchain = mock(Blockchain.class);
  private final PrivacyParameters privacyParameters = mock(PrivacyParameters.class);
  private final PrivateStateStorage privateStateStorage = mock(PrivateStateStorage.class);
  private final PrivacyController privacyController = mock(PrivacyController.class);

  @Before
  public void setUp() {
    when(privacyController.retrieveTransaction(anyString(), any()))
        .thenReturn(
            new ReceiveResponse(
                Base64.getEncoder().encode(RLP.encode(privateTransaction::writeTo).toArray()),
                "",
                null));

    when(blockchainQueries.getBlockchain()).thenReturn(blockchain);
    final TransactionLocation transactionLocation = new TransactionLocation(Hash.EMPTY, 0);
    when(blockchain.getTransactionLocation(nullable(Hash.class)))
        .thenReturn(Optional.of(transactionLocation));
    final BlockBody blockBody =
        new BlockBody(Collections.singletonList(transaction), Collections.emptyList());
    when(blockchain.getBlockBody(any(Hash.class))).thenReturn(Optional.of(blockBody));
    final BlockHeader mockBlockHeader = mock(BlockHeader.class);
    when(blockchain.getBlockHeader(any(Hash.class))).thenReturn(Optional.of(mockBlockHeader));
    when(mockBlockHeader.getNumber()).thenReturn(0L);

    when(privacyParameters.isEnabled()).thenReturn(true);
    when(privacyParameters.getPrivateStateStorage()).thenReturn(privateStateStorage);
    when(privateStateStorage.getTransactionLogs(any(Bytes32.class))).thenReturn(Optional.empty());
    when(privateStateStorage.getTransactionOutput(any(Bytes32.class))).thenReturn(Optional.empty());
    when(privateStateStorage.getStatus(any(Bytes32.class))).thenReturn(Optional.of(Bytes.of(1)));
  }

  @Test
  public void returnReceiptIfTransactionExists() {

    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequestContext request =
        new JsonRpcRequestContext(
            new JsonRpcRequest("1", "priv_getTransactionReceipt", params), user);

    final JsonRpcSuccessResponse response =
        (JsonRpcSuccessResponse) privGetTransactionReceipt.response(request);
    final PrivateTransactionReceiptResult result =
        (PrivateTransactionReceiptResult) response.getResult();

    assertThat(result).isEqualToComparingFieldByField(expectedResult);
    verify(privacyController).retrieveTransaction(ENCLAVE_KEY.toBase64String(), ENCLAVE_PUBLIC_KEY);
  }

  @Test
  public void enclavePayloadNotFoundResultsInSuccessButNullResponse() {
    when(privacyController.retrieveTransaction(anyString(), any()))
        .thenThrow(new EnclaveClientException(404, "EnclavePayloadNotFound"));

    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequestContext request =
        new JsonRpcRequestContext(new JsonRpcRequest("1", "priv_getTransactionReceipt", params));

    final JsonRpcSuccessResponse response =
        (JsonRpcSuccessResponse) privGetTransactionReceipt.response(request);
    final PrivateTransactionReceiptResult result =
        (PrivateTransactionReceiptResult) response.getResult();

    assertThat(result).isNull();
  }

  @Test
  public void markerTransactionNotAvailableResultsInNullResponse() {
    when(blockchain.getTransactionLocation(nullable(Hash.class))).thenReturn(Optional.empty());

    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequestContext request =
        new JsonRpcRequestContext(new JsonRpcRequest("1", "priv_getTransactionReceipt", params));

    final JsonRpcSuccessResponse response =
        (JsonRpcSuccessResponse) privGetTransactionReceipt.response(request);
    final PrivateTransactionReceiptResult result =
        (PrivateTransactionReceiptResult) response.getResult();

    assertThat(result).isNull();
  }

  @Test
  public void enclaveConnectionIssueThrowsRuntimeException() {
    when(privacyController.retrieveTransaction(anyString(), any()))
        .thenThrow(EnclaveServerException.class);
    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequestContext request =
        new JsonRpcRequestContext(new JsonRpcRequest("1", "priv_getTransactionReceipt", params));

    final Throwable t = catchThrowable(() -> privGetTransactionReceipt.response(request));
    assertThat(t).isInstanceOf(RuntimeException.class);
  }

  @Test
  public void transactionReceiptContainsRevertReasonWhenInvalidTransactionOccurs() {
    when(privateStateStorage.getRevertReason(any(Bytes32.class)))
        .thenReturn(Optional.of(Bytes.fromHexString("0x01")));

    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequest request = new JsonRpcRequest("1", "priv_getTransactionReceipt", params);

    final JsonRpcSuccessResponse response =
        (JsonRpcSuccessResponse)
            privGetTransactionReceipt.response(new JsonRpcRequestContext(request));
    final PrivateTransactionReceiptResult result =
        (PrivateTransactionReceiptResult) response.getResult();

    assertThat(result.getRevertReason()).isEqualTo("0x01");
  }

  @Test
  public void enclaveKeysCannotDecryptPayloadThrowsRuntimeException() {
    final String keysCannotDecryptPayloadMsg = "EnclaveKeysCannotDecryptPayload";
    when(privacyController.retrieveTransaction(any(), any()))
        .thenThrow(new EnclaveClientException(400, keysCannotDecryptPayloadMsg));

    final PrivGetTransactionReceipt privGetTransactionReceipt =
        new PrivGetTransactionReceipt(
            blockchainQueries, privacyParameters, privacyController, enclavePublicKeyProvider);
    final Object[] params = new Object[] {transaction.getHash()};
    final JsonRpcRequestContext request =
        new JsonRpcRequestContext(new JsonRpcRequest("1", "priv_getTransactionReceipt", params));

    final Throwable t = catchThrowable(() -> privGetTransactionReceipt.response(request));
    assertThat(t).isInstanceOf(EnclaveClientException.class);
    assertThat(t.getMessage()).isEqualTo(keysCannotDecryptPayloadMsg);
  }
}
