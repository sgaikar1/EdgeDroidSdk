// Android Vulkan loader shim.
//
// The NDK's libvulkan.so stub exports Vulkan 1.0 core functions only, and
// ggml-vulkan calls vkGetPhysicalDeviceFeatures2 (Vulkan 1.1 core) directly.
// This shim defines that one symbol, resolving the real implementation through the
// loader's instance-agnostic lookup (vkGetInstanceProcAddr(NULL, ...)) so it works on
// every device, without linking a full Vulkan loader at build time.

#include <vulkan/vulkan.h>

extern "C" void vkGetPhysicalDeviceFeatures2(
        VkPhysicalDevice physicalDevice,
        VkPhysicalDeviceFeatures2 * pFeatures) {
    static PFN_vkGetPhysicalDeviceFeatures2 real = nullptr;
    if (real == nullptr) {
        real = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
                vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkGetPhysicalDeviceFeatures2"));
        if (real == nullptr) {
            return;
        }
    }
    real(physicalDevice, pFeatures);
}
