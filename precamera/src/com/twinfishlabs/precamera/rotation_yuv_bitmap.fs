#pragma version(1)
#pragma rs java_package_name(com.twinfishlabs.precamcorder)

rs_allocation in;

uint32_t srcImgWidth;
uint32_t srcImgHeight;
uint32_t srcImgHeightMinusOne;
uint32_t srcImgHeightHalfMinusOne;

uchar __attribute__((kernel)) root(uint32_t x, uint32_t y) {
    if (y < srcImgWidth) { // is Luma
        return rsGetElementAt_uchar(in, y, srcImgHeightMinusOne - x);
    } else {
        return rsGetElementAt_uchar(in,
            (y - srcImgWidth) * 2 + x % 2,
            srcImgHeightHalfMinusOne - x / 2 + srcImgHeight);
    }
}
