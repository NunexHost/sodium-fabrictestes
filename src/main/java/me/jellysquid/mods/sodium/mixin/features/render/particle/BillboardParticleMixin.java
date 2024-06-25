package me.jellysquid.mods.sodium.mixin.features.render.particle;

import net.caffeinemc.mods.sodium.api.vertex.format.common.ParticleVertex;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.world.ClientWorld;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BillboardParticle.class)
public abstract class BillboardParticleMixin extends Particle {
    @Shadow
    public abstract float getSize(float tickDelta);

    @Shadow
    protected abstract float getMinU();

    @Shadow
    protected abstract float getMaxU();

    @Shadow
    protected abstract float getMinV();

    @Shadow
    protected abstract float getMaxV();

    @Unique
    private Vector3f[] vertices = new Vector3f[4];

    protected BillboardParticleMixin(ClientWorld level, double x, double y, double z) {
        super(level, x, y, z);
        for (int i = 0; i < 4; i++) {
            vertices[i] = new Vector3f();
        }
    }

    /**
     * @reason Optimize function
     */
    @Overwrite
    protected void method_60374(VertexConsumer vertexConsumer, Quaternionf quaternionf, float x, float y, float z, float tickDelta) {
        float size = this.getSize(tickDelta);
        float minU = this.getMinU();
        float maxU = this.getMaxU();
        float minV = this.getMinV();
        float maxV = this.getMaxV();
        int light = this.getBrightness(tickDelta);

        int color = ColorABGR.pack(this.red, this.green, this.blue, this.alpha);

        // Loop to write vertices
        for (int i = 0; i < 4; i++) {
            float posX, posY;
            switch (i) {
                case 0:
                    posX = 1.0F;
                    posY = -1.0F;
                    break;
                case 1:
                    posX = 1.0F;
                    posY = 1.0F;
                    break;
                case 2:
                    posX = -1.0F;
                    posY = 1.0F;
                    break;
                case 3:
                    posX = -1.0F;
                    posY = -1.0F;
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + i);
            }

            Vector3f transferVector = vertices[i];
            transferVector.set(posX, posY, 0.0f);
            transferVector.rotate(quaternionf);
            transferVector.mul(size);
            transferVector.add(x, y, z);

            ParticleVertex.put(vertexConsumer, transferVector.x(), transferVector.y(), transferVector.z(),
                    minU + (maxU - minU) * (posX + 1.0F) / 2.0F, minV + (maxV - minV) * (posY + 1.0F) / 2.0F,
                    color, light);
        }
    }
}
