package com.chat.talkMe.service.impl;

import com.chat.talkMe.domain.Device;
import com.chat.talkMe.domain.User;
import com.chat.talkMe.dto.request.RegisterDeviceRequest;
import com.chat.talkMe.exception.ForbiddenException;
import com.chat.talkMe.exception.NotFoundException;
import com.chat.talkMe.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit test for {@link DeviceServiceImpl} — push-notification device token
 * registry. Covers register (update-existing-ownership vs create-new branches) and
 * unregister (not-found → {@code TM_002}, ownership guard → {@code TM_029}, happy delete).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceServiceImpl (unit)")
class DeviceServiceImplTest {

    private static final String TOKEN = "fcm-token-abc";

    @Mock private DeviceRepository deviceRepository;

    private DeviceServiceImpl service;

    private User owner;

    @BeforeEach
    void setUp() {
        service = new DeviceServiceImpl(deviceRepository);
        owner = User.builder().username("alice").build();
        owner.setId(1L);
    }

    private RegisterDeviceRequest request() {
        return RegisterDeviceRequest.builder()
                .deviceToken(TOKEN)
                .deviceType("ANDROID")
                .osVersion("14")
                .build();
    }

    @Nested
    @DisplayName("registerDevice")
    class RegisterDevice {

        @Test
        @DisplayName("no existing token → builds and saves a brand-new device for the user")
        void createsNewDevice() {
            when(deviceRepository.findByDeviceToken(TOKEN)).thenReturn(Optional.empty());

            service.registerDevice(request(), owner);

            ArgumentCaptor<Device> saved = ArgumentCaptor.forClass(Device.class);
            verify(deviceRepository).save(saved.capture());
            Device d = saved.getValue();
            assertThat(d.getUser()).isSameAs(owner);
            assertThat(d.getDeviceToken()).isEqualTo(TOKEN);
            assertThat(d.getDeviceType()).isEqualTo("ANDROID");
            assertThat(d.getOsVersion()).isEqualTo("14");
        }

        @Test
        @DisplayName("existing token → re-points ownership and updates type/os, saves same row")
        void updatesExistingDeviceOwnership() {
            User previousOwner = User.builder().username("bob").build();
            previousOwner.setId(99L);
            Device existing = Device.builder()
                    .user(previousOwner)
                    .deviceToken(TOKEN)
                    .deviceType("IOS")
                    .osVersion("16")
                    .build();
            when(deviceRepository.findByDeviceToken(TOKEN)).thenReturn(Optional.of(existing));

            service.registerDevice(request(), owner);

            ArgumentCaptor<Device> saved = ArgumentCaptor.forClass(Device.class);
            verify(deviceRepository).save(saved.capture());
            Device d = saved.getValue();
            assertThat(d).isSameAs(existing);
            assertThat(d.getUser()).isSameAs(owner);
            assertThat(d.getDeviceType()).isEqualTo("ANDROID");
            assertThat(d.getOsVersion()).isEqualTo("14");
        }
    }

    @Nested
    @DisplayName("unregisterDevice")
    class UnregisterDevice {

        @Test
        @DisplayName("token missing → NotFoundException TM_002, nothing deleted")
        void throwsWhenTokenMissing() {
            when(deviceRepository.findByDeviceToken(TOKEN)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.unregisterDevice(TOKEN, owner))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_002"));
            verify(deviceRepository, never()).delete(any());
        }

        @Test
        @DisplayName("owned by current user → deletes the device")
        void deletesWhenOwner() {
            Device device = Device.builder().user(owner).deviceToken(TOKEN).build();
            when(deviceRepository.findByDeviceToken(TOKEN)).thenReturn(Optional.of(device));

            service.unregisterDevice(TOKEN, owner);

            verify(deviceRepository).delete(device);
        }

        @Test
        @DisplayName("owned by another user → ForbiddenException TM_029, nothing deleted")
        void throwsWhenNotOwner() {
            User otherOwner = User.builder().username("mallory").build();
            otherOwner.setId(2L);
            Device device = Device.builder().user(otherOwner).deviceToken(TOKEN).build();
            when(deviceRepository.findByDeviceToken(TOKEN)).thenReturn(Optional.of(device));

            assertThatThrownBy(() -> service.unregisterDevice(TOKEN, owner))
                    .isInstanceOfSatisfying(ForbiddenException.class,
                            ex -> assertThat(ex.getMessageCode()).isEqualTo("TM_029"));
            verify(deviceRepository, never()).delete(any());
        }
    }
}
