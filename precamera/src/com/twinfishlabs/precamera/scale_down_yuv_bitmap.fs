#pragma version(1)
#pragma rs java_package_name(com.twinfishlabs.precamcorder)

rs_allocation in;

uint32_t srcImgWidth;
uint32_t srcImgHeight;
uint32_t dstImgWidth;
uint32_t dstImgHeight;
float widthRatio;
float heightRatio;

uchar __attribute__((kernel)) root(uint32_t x, uint32_t y) {
    if (y < dstImgHeight) { // is Luma
        uint32_t srcX = (uint32_t)(round(x * widthRatio));
        uint32_t srcY = (uint32_t)(round(y * heightRatio));
        return rsGetElementAt_uchar(in, srcX, srcY);
    } else {
        y -= dstImgHeight;
        uint32_t uvIndex = x / 2;
        uint32_t srcX = (uint32_t)(round(uvIndex * widthRatio));
        srcX = srcX * 2 + x % 2;
        uint32_t srcY = (uint32_t)(round(y * heightRatio)) + srcImgHeight;
        return rsGetElementAt_uchar(in, srcX, srcY);
    }
}
