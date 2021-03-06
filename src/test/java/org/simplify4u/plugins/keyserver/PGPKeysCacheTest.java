/*
 * Copyright 2020 Slawomir Jaranowski
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplify4u.plugins.keyserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.io.ByteStreams;
import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.simplify4u.plugins.keyserver.PGPKeysCache.KeyServerList;
import org.simplify4u.plugins.keyserver.PGPKeysCache.KeyServerListFallback;
import org.simplify4u.plugins.keyserver.PGPKeysCache.KeyServerListLoadBalance;
import org.simplify4u.plugins.keyserver.PGPKeysCache.KeyServerListOne;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class PGPKeysCacheTest {

    private Path cachePath;
    private List<PGPKeysServerClient> keysServerClients;

    @BeforeMethod
    public void setup() throws IOException {
        cachePath = Files.createTempDirectory("cache-path-test");
        PGPKeysServerClient keysServerClient = mock(PGPKeysServerClient.class);

        doAnswer(i -> new URI(String.format("https://key.get.example.com/?keyId=%016x", (long) i.getArgument(0))))
                .when(keysServerClient).getUriForGetKey(anyLong());


        doAnswer(i -> {
            try (InputStream inputStream = getClass().getResourceAsStream("/EFE8086F9E93774E.asc")) {
                ByteStreams.copy(inputStream, i.getArgument(1));
            }
            return null;
        }).when(keysServerClient).copyKeyToOutputStream(anyLong(), any(OutputStream.class), any(PGPKeysServerClient.OnRetryConsumer.class));

        keysServerClients = Collections.singletonList(keysServerClient);
    }

    @AfterMethod
    public void cleanup() throws IOException {
        MoreFiles.deleteRecursively(cachePath, RecursiveDeleteOption.ALLOW_INSECURE);
    }

    @Test
    public void emptyCacheDirShouldBeCreated() throws IOException {

        File emptyCachePath = new File(cachePath.toFile(), "empty");

        assertThat(emptyCachePath).doesNotExist();

        new PGPKeysCache(emptyCachePath, keysServerClients, true);

        assertThat(emptyCachePath)
                .exists()
                .isDirectory();
    }

    @Test
    public void fileAsCacheDirThrowException() throws IOException {

        File fileAsCachePath = new File(cachePath.toFile(), "file.tmp");
        MoreFiles.touch(fileAsCachePath.toPath());

        assertThat(fileAsCachePath)
                .exists()
                .isFile();

        assertThatCode(() -> new PGPKeysCache(fileAsCachePath, keysServerClients, true))
                .isExactlyInstanceOf(IOException.class)
                .hasMessageStartingWith("PGP keys cache path exist but is not a directory:");
    }

    @Test
    public void getKeyFromCache() throws IOException, PGPException {

        PGPKeysCache pgpKeysCache = new PGPKeysCache(cachePath.toFile(), keysServerClients, true);

        // first call retrieve key from server
        PGPPublicKeyRing keyRing = pgpKeysCache.getKeyRing(0xEFE8086F9E93774EL);

        assertThat(keyRing)
                .hasSize(2)
                .anyMatch(key -> key.getKeyID() == 0xEFE8086F9E93774EL);

        verify(keysServerClients.get(0)).getUriForGetKey(anyLong());
        verify(keysServerClients.get(0)).copyKeyToOutputStream(anyLong(), any(OutputStream.class), any(PGPKeysServerClient.OnRetryConsumer.class));
        verifyNoMoreInteractions(keysServerClients.get(0));
        clearInvocations(keysServerClients.get(0));

        // second from cache
        keyRing = pgpKeysCache.getKeyRing(0xEFE8086F9E93774EL);

        assertThat(keyRing)
                .hasSize(2)
                .anyMatch(key -> key.getKeyID() == 0xEFE8086F9E93774EL);

        verifyNoInteractions(keysServerClients.get(0));
    }

    @Test
    public void nonExistingKeyInRingThrowException() throws IOException, PGPException {

        PGPKeysCache pgpKeysCache = new PGPKeysCache(cachePath.toFile(), keysServerClients, true);

        // first call retrieve key from server
        assertThatCode(() -> pgpKeysCache.getKeyRing(0x1234567890L))
                .isExactlyInstanceOf(PGPException.class)
                .hasMessageStartingWith("Can't find public key 0x0000001234567890 in download file:");
    }

    @DataProvider(name = "serverListTestData")
    public Object[][] serverListTestData() {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        return new Object[][]{
                {Collections.singletonList(client1), true, KeyServerListOne.class},
                {Collections.singletonList(client1), false, KeyServerListOne.class},
                {Arrays.asList(client1, client2), true, KeyServerListLoadBalance.class},
                {Arrays.asList(client1, client2), false, KeyServerListFallback.class}
        };
    }

    @Test(dataProvider = "serverListTestData")
    public void createKeyServerListReturnCorrectImplementation(
            List<PGPKeysServerClient> serverList, boolean loadBalance, Class<? extends KeyServerList> aClass) {

        KeyServerList keyServerList = PGPKeysCache.createKeyServerList(serverList, loadBalance);

        assertThat(keyServerList).isExactlyInstanceOf(aClass);
    }

    @Test
    public void listOneUseFirstServerForCorrectExecute() throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        List<PGPKeysServerClient> executedClient = new ArrayList<>();

        KeyServerList serverList = new KeyServerListOne().withClients(Arrays.asList(client1, client2));

        for (int i = 0; i < 2; i++) {
            serverList.execute(client -> {
                client.copyKeyToOutputStream(1, null, null);
                executedClient.add(client);
            });
            serverList.getUriForShowKey(1);
        }

        assertThat(executedClient).containsOnly(client1, client1);
        verify(client1, times(2)).copyKeyToOutputStream(1, null, null);
        verify(client1, times(2)).getUriForShowKey(1);
        verifyNoMoreInteractions(client1);
        verifyNoInteractions(client2);
    }

    @Test
    public void listOneThrowsExceptionForFailedExecute() throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        doThrow(new IOException("Fallback test")).when(client1).copyKeyToOutputStream(1, null, null);

        KeyServerList serverListFallback = new KeyServerListOne().withClients(Arrays.asList(client1, client2));

        assertThatCode(() ->
                serverListFallback.execute(client ->
                        client.copyKeyToOutputStream(1, null, null)))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("Fallback test");

        verify(client1).copyKeyToOutputStream(1, null, null);
        verifyNoMoreInteractions(client1);
        verifyNoInteractions(client2);
    }

    @Test
    public void fallbackOnlyUseFirstServerForCorrectExecute() throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        List<PGPKeysServerClient> executedClient = new ArrayList<>();

        KeyServerList serverListFallback = new KeyServerListFallback().withClients(Arrays.asList(client1, client2));

        for (int i = 0; i < 2; i++) {
            serverListFallback.execute(client -> {
                client.copyKeyToOutputStream(1, null, null);
                executedClient.add(client);
            });
            serverListFallback.getUriForShowKey(1);
        }

        assertThat(executedClient).containsOnly(client1, client1);
        verify(client1, times(2)).copyKeyToOutputStream(1, null, null);
        verify(client1, times(2)).getUriForShowKey(1);
        verifyNoMoreInteractions(client1);
        verifyNoInteractions(client2);
    }

    @Test
    public void loadBalanceIterateByAllServer() throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        List<PGPKeysServerClient> executedClient = new ArrayList<>();

        KeyServerList serverListFallback = new KeyServerListLoadBalance().withClients(Arrays.asList(client1, client2));

        for (int i = 0; i < 3; i++) {
            serverListFallback.execute(client -> {
                client.copyKeyToOutputStream(1, null, null);
                executedClient.add(client);
            });
            serverListFallback.getUriForShowKey(1);
        }

        assertThat(executedClient).containsExactly(client1, client2, client1);

        verify(client1, times(2)).copyKeyToOutputStream(1, null, null);
        verify(client1, times(2)).getUriForShowKey(1);
        verifyNoMoreInteractions(client1);

        verify(client2).copyKeyToOutputStream(1, null, null);
        verify(client2).getUriForShowKey(1);
        verifyNoMoreInteractions(client1);
    }

    @DataProvider(name = "keyServerListWithFallBack")
    public Object[] keyServerListWithFallBack() {

        return new Object[]{
                new KeyServerListFallback(),
                new KeyServerListLoadBalance()
        };
    }

    @Test(dataProvider = "keyServerListWithFallBack")
    public void useSecondServerForFailedExecute(KeyServerList keyServerList) throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        doThrow(new IOException("Fallback test")).when(client1).copyKeyToOutputStream(1, null, null);

        keyServerList.withClients(Arrays.asList(client1, client2));

        List<PGPKeysServerClient> executedClient = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            keyServerList.execute(client -> {
                client.copyKeyToOutputStream(1, null, null);
                executedClient.add(client);
            });
            keyServerList.getUriForShowKey(1);
        }

        assertThat(executedClient).containsExactly(client2, client2);

        verify(client1, times(2)).copyKeyToOutputStream(1, null, null);
        verifyNoMoreInteractions(client1);

        verify(client2, times(2)).copyKeyToOutputStream(1, null, null);
        verify(client2, times(2)).getUriForShowKey(1);

        verifyNoMoreInteractions(client2);
    }

    @Test(dataProvider = "keyServerListWithFallBack")
    public void throwsExceptionForAllFailedExecute(KeyServerList keyServerList) throws IOException {

        PGPKeysServerClient client1 = mock(PGPKeysServerClient.class);
        PGPKeysServerClient client2 = mock(PGPKeysServerClient.class);

        doThrow(new IOException("Fallback test1")).when(client1).copyKeyToOutputStream(1, null, null);
        doThrow(new IOException("Fallback test2")).when(client2).copyKeyToOutputStream(1, null, null);

        keyServerList.withClients(Arrays.asList(client1, client2));

        assertThatCode(() ->
                keyServerList.execute(client ->
                        client.copyKeyToOutputStream(1, null, null)))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("All servers from list was failed")
                .hasCauseExactlyInstanceOf(IOException.class)
                .hasRootCauseMessage("Fallback test2");


        verify(client1).copyKeyToOutputStream(1, null, null);
        verifyNoMoreInteractions(client1);

        verify(client2).copyKeyToOutputStream(1, null, null);
        verifyNoMoreInteractions(client2);
    }
}
