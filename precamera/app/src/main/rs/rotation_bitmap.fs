#pragma version(1)
#pragma rs java_package_name(com.twinfishlabs.precamcorder)

rs_allocation in;

uint32_t dstWidthMinusOne;

uchar4 __attribute__((kernel)) root(uint32_t x, uint32_t y) {
    return rsGetElementAt_uchar4(in, y, dstWidthMinusOne - x);
}
