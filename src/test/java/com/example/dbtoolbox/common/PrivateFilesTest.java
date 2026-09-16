package com.example.dbtoolbox.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** ACL policy checks on a fake view; these do not constitute Windows filesystem acceptance. */
class PrivateFilesTest {
    @Test void aclUsesTheActualFileOwnerRatherThanTheLoginName() throws Exception {
        UserPrincipal owner = () -> "fixture-domain/file-owner";
        AclFileAttributeView view = mock(AclFileAttributeView.class);
        when(view.getOwner()).thenReturn(owner);
        AtomicReference<List<AclEntry>> written = new AtomicReference<>();
        doAnswer(call -> { written.set(call.getArgument(0)); return null; }).when(view).setAcl(anyList());
        when(view.getAcl()).thenAnswer(call -> written.get());

        PrivateFiles.protectAcl(view, false);

        assertEquals(1, written.get().size());
        assertSame(owner, written.get().get(0).principal());
        assertEquals(AclEntryType.ALLOW, written.get().get(0).type());
    }

    @Test void providerRetainingAnUnexpectedAceFailsClosed() throws Exception {
        AclFileAttributeView view = mock(AclFileAttributeView.class);
        when(view.getOwner()).thenReturn(() -> "fixture-owner");
        AclEntry other = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(() -> "fixture-other-account")
                .setPermissions(AclEntryPermission.READ_DATA).build();
        when(view.getAcl()).thenReturn(Collections.singletonList(other));

        assertThrows(IOException.class, () -> PrivateFiles.protectAcl(view, false));
    }
}
